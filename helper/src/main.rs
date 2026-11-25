//! LocalTube helper: lets the LocalTube web page browse and play files from SMB shares on THIS computer's network.
//!
//! A browser can't speak SMB, and the LocalTube server may sit on another network. This small program runs on the client's side,
//! finds SMB hosts on the local network, signs in with the credentials you type into the web page, and serves folder listings
//! and file bytes (with HTTP Range, so seeking works) to the page over plain HTTP on 127.0.0.1.
//!
//! Safety: it only listens on the loopback address, answers only requests that carry the pairing code, rejects other Host
//! headers (DNS rebinding), and allows cross-origin calls only from the origins you list. Passwords stay in memory.

use axum::{
    Json, Router,
    body::Body,
    extract::{Query, State},
    http::{HeaderMap, HeaderValue, Request, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use smb::{Client, ClientConfig, Directory, FileAccessMask, FileCreateArgs, GetLen, ReadAt, UncPath};
use smb::{DirAccessMask, FileDirectoryInformation};
use std::{
    collections::{HashMap, HashSet},
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::{Path, PathBuf},
    str::FromStr,
    sync::Arc,
    time::Duration,
};
use tokio::{io::AsyncReadExt, io::AsyncSeekExt, net::TcpStream, sync::Mutex, time::timeout};
use tower_http::cors::{AllowOrigin, CorsLayer};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const DEFAULT_PORT: u16 = 47821;

struct Sess {
    client: Client,
    host: String,
    user: String,
    pass: String,
    connected: Mutex<HashSet<String>>, // shares already signed in to
    demo: Option<PathBuf>,             // --demo <dir>: serve a local folder as a pretend SMB host (for trying the web page)
}

struct App {
    token: String,
    port: u16,
    demo: Option<PathBuf>,
    sessions: Mutex<HashMap<String, Arc<Sess>>>,
}
type Shared = Arc<App>;
type Res<T> = Result<T, (StatusCode, String)>;

fn bad<E: std::fmt::Display>(code: StatusCode) -> impl Fn(E) -> (StatusCode, String) {
    move |e| (code, e.to_string())
}

// ---------------------------------------------------------------- startup

fn token_path() -> PathBuf {
    let base = std::env::var_os("APPDATA").or_else(|| std::env::var_os("XDG_CONFIG_HOME")).map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
        .unwrap_or_else(|| PathBuf::from("."));
    base.join("LocalTubeHelper").join("pairing-code.txt")
}

/// The pairing code is made once and kept, so the browser only has to be paired the first time.
fn load_or_make_token() -> String {
    let p = token_path();
    if let Ok(t) = std::fs::read_to_string(&p) {
        let t = t.trim().to_string();
        if t.len() >= 12 { return t; }
    }
    let t: String = (0..12).map(|_| b"abcdefghjkmnpqrstuvwxyz23456789"[rand::random_range(0..31usize)] as char).collect();
    if let Some(d) = p.parent() { let _ = std::fs::create_dir_all(d); }
    let _ = std::fs::write(&p, &t);
    t
}

#[tokio::main]
async fn main() {
    let mut port = DEFAULT_PORT;
    let mut origins: Vec<String> = vec!["http://localhost:3000".into(), "http://127.0.0.1:3000".into()];
    let mut demo = None;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--port" => port = args.next().and_then(|v| v.parse().ok()).unwrap_or(DEFAULT_PORT),
            "--origin" => if let Some(o) = args.next() { origins.push(o.trim_end_matches('/').to_string()) },
            "--demo" => demo = args.next().map(PathBuf::from),
            "-h" | "--help" => {
                println!("LocalTube helper {VERSION}\n\nUsage: localtube-helper [--origin <portal address>]... [--port <n>] [--demo <folder>]\n\n  --origin  address of the LocalTube portal that may use this helper, e.g. http://192.168.1.20:3000 (repeatable)\n  --port    port to listen on (default {DEFAULT_PORT})\n  --demo    serve a local folder as a pretend SMB server, to try the web page without a real share");
                return;
            }
            other => eprintln!("ignoring unknown option {other}"),
        }
    }
    let token = load_or_make_token();
    let app = Arc::new(App { token: token.clone(), port, demo, sessions: Mutex::new(HashMap::new()) });

    let cors = CorsLayer::new()
        .allow_origin(AllowOrigin::predicate({
            let origins = origins.clone();
            move |o: &HeaderValue, _| o.to_str().map(|o| origins.iter().any(|x| x == o)).unwrap_or(false)
        }))
        .allow_methods([axum::http::Method::GET, axum::http::Method::POST, axum::http::Method::OPTIONS])
        .allow_headers([header::CONTENT_TYPE, header::RANGE])
        .expose_headers([header::CONTENT_RANGE, header::ACCEPT_RANGES, header::CONTENT_LENGTH])
        .allow_private_network(true);

    let router = Router::new()
        .route("/ping", get(ping))
        .route("/discover", get(discover))
        .route("/connect", post(connect))
        .route("/ls", get(ls))
        .route("/file", get(file))
        .layer(middleware::from_fn_with_state(app.clone(), guard))
        .layer(cors)
        .with_state(app.clone());

    let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => { eprintln!("Can't listen on {addr}: {e}. Is the helper already running?"); return; }
    };
    println!("LocalTube helper {VERSION} is running on http://{addr}");
    println!("Pairing code: {token}");
    println!("In the LocalTube web page: Network > Find on the network, then type that code once.");
    println!("Allowed portals: {}", origins.join(", "));
    if let Some(d) = &app.demo { println!("DEMO MODE: serving {} as the pretend host \"demo\"", d.display()); }
    axum::serve(listener, router).await.unwrap();
}

// ---------------------------------------------------------------- guard: loopback Host only, pairing code required

async fn guard(State(app): State<Shared>, req: Request<Body>, next: Next) -> Response {
    if req.method() == axum::http::Method::OPTIONS { return next.run(req).await; } // CORS preflights carry no code
    let host_ok = req.headers().get(header::HOST).and_then(|h| h.to_str().ok()).map(|h| {
        let h = h.to_ascii_lowercase();
        h == format!("127.0.0.1:{}", app.port) || h == format!("localhost:{}", app.port)
    }).unwrap_or(false);
    if !host_ok { return (StatusCode::FORBIDDEN, "bad host").into_response(); }
    if req.uri().path() == "/ping" { return next.run(req).await; }
    let q = req.uri().query().unwrap_or("");
    let given = q.split('&').find_map(|kv| kv.strip_prefix("t=")).unwrap_or("");
    if given != app.token { return (StatusCode::UNAUTHORIZED, "wrong or missing pairing code").into_response(); }
    next.run(req).await
}

// ---------------------------------------------------------------- /ping

#[derive(Serialize)]
struct Ping { app: &'static str, version: &'static str, demo: bool }
async fn ping(State(app): State<Shared>) -> Json<Ping> { Json(Ping { app: "localtube-helper", version: VERSION, demo: app.demo.is_some() }) }

// ---------------------------------------------------------------- /discover: who answers on the SMB port nearby?

#[derive(Serialize)]
struct Host { ip: String, this_computer: bool }

fn local_subnets() -> Vec<(Ipv4Addr, Ipv4Addr)> {
    let mut v = Vec::new();
    for i in if_addrs::get_if_addrs().unwrap_or_default() {
        if let if_addrs::IfAddr::V4(a) = i.addr {
            if a.ip.is_loopback() || a.ip.is_link_local() { continue; }
            v.push((a.ip, a.netmask));
        }
    }
    v
}

async fn discover(State(app): State<Shared>) -> Json<Vec<Host>> {
    if app.demo.is_some() { return Json(vec![Host { ip: "demo".into(), this_computer: false }]); }
    let subnets = local_subnets();
    let mine: HashSet<Ipv4Addr> = subnets.iter().map(|s| s.0).collect();
    let mut targets: HashSet<Ipv4Addr> = HashSet::new();
    for (ip, mask) in &subnets {
        // never scan more than a /24: a big network would take minutes and is not what a home or office share needs
        let mask = u32::from(*mask) | 0xFFFF_FF00;
        let net = u32::from(*ip) & mask;
        for n in 1..(!mask) { targets.insert(Ipv4Addr::from(net + n)); }
    }
    let found: Vec<Ipv4Addr> = futures_util::stream::iter(targets)
        .map(|ip| async move {
            let ok = timeout(Duration::from_millis(600), TcpStream::connect((ip, 445))).await.map(|r| r.is_ok()).unwrap_or(false);
            ok.then_some(ip)
        })
        .buffer_unordered(128).filter_map(|x| async move { x }).collect().await;
    let mut hosts: Vec<Host> = found.into_iter().map(|ip| Host { ip: ip.to_string(), this_computer: mine.contains(&ip) }).collect();
    hosts.sort_by(|a, b| a.ip.parse::<Ipv4Addr>().ok().cmp(&b.ip.parse::<Ipv4Addr>().ok()));
    Json(hosts)
}

// ---------------------------------------------------------------- /connect: sign in, list the shares

#[derive(Deserialize)]
struct ConnectReq { host: String, #[serde(default)] user: String, #[serde(default)] pass: String }
#[derive(Serialize)]
struct ConnectResp { session: String, shares: Vec<String> }

async fn connect(State(app): State<Shared>, Json(r): Json<ConnectReq>) -> Res<Json<ConnectResp>> {
    let id: String = (0..16).map(|_| b"abcdefghijklmnopqrstuvwxyz0123456789"[rand::random_range(0..36usize)] as char).collect();
    if let Some(dir) = &app.demo {
        let s = Arc::new(Sess { client: Client::new(ClientConfig::default()), host: "demo".into(), user: String::new(), pass: String::new(), connected: Mutex::new(HashSet::new()), demo: Some(dir.clone()) });
        app.sessions.lock().await.insert(id.clone(), s);
        return Ok(Json(ConnectResp { session: id, shares: vec!["Demo".into()] }));
    }
    // blank user = guest
    let (user, pass) = if r.user.trim().is_empty() { ("guest".to_string(), String::new()) } else { (r.user.trim().to_string(), r.pass.clone()) };
    let client = Client::new(ClientConfig::default());
    timeout(Duration::from_secs(15), client.ipc_connect(&r.host, &user, pass.clone())).await
        .map_err(|_| (StatusCode::GATEWAY_TIMEOUT, format!("{} didn't answer", r.host)))?
        .map_err(|e| friendly(&e.to_string(), &r.host))?;
    let shares = client.list_shares(&r.host).await.map_err(|e| friendly(&e.to_string(), &r.host))?;
    let mut names: Vec<String> = shares.iter().map(|s| s.netname.as_ref().map(|n| n.to_string()).unwrap_or_default()).filter(|n| !n.ends_with('$')).collect();
    names.sort_by_key(|n| n.to_lowercase());
    let s = Arc::new(Sess { client, host: r.host.clone(), user, pass, connected: Mutex::new(HashSet::new()), demo: None });
    app.sessions.lock().await.insert(id.clone(), s);
    Ok(Json(ConnectResp { session: id, shares: names }))
}

/// Windows/Samba answer sign-in problems with NT status codes; say what they mean.
fn friendly(raw: &str, host: &str) -> (StatusCode, String) {
    let r = raw.to_ascii_lowercase();
    if r.contains("c000006d") || r.contains("c000006a") || r.contains("c000006e") { (StatusCode::UNAUTHORIZED, "Wrong user name or password.".into()) }
    else if r.contains("c0000072") { (StatusCode::UNAUTHORIZED, "That account is disabled. Guest sign-in is turned off on this computer, so enter a user name and password.".into()) }
    else if r.contains("c0000234") { (StatusCode::UNAUTHORIZED, "That account is locked out.".into()) }
    else if r.contains("c000015b") || r.contains("c0000022") { (StatusCode::FORBIDDEN, "That account isn't allowed to use this share.".into()) }
    else if r.contains("timed out") || r.contains("transport") || r.contains("refused") || r.contains("unreachable") { (StatusCode::GATEWAY_TIMEOUT, format!("Can't reach {host}. Is it on, and is file sharing enabled?")) }
    else { (StatusCode::BAD_GATEWAY, raw.to_string()) }
}

// ---------------------------------------------------------------- /ls and /file

#[derive(Deserialize)]
struct PathQ { s: String, share: String, #[serde(default)] path: String }

async fn session(app: &Shared, id: &str) -> Res<Arc<Sess>> {
    app.sessions.lock().await.get(id).cloned().ok_or((StatusCode::NOT_FOUND, "unknown session: connect again".into()))
}

/// Sign in to one share once, then reuse it.
async fn share_ready(s: &Sess, share: &str) -> Res<UncPath> {
    let unc = UncPath::from_str(&format!(r"\\{}\{}", s.host, share)).map_err(bad(StatusCode::BAD_REQUEST))?;
    let mut done = s.connected.lock().await;
    if !done.contains(share) {
        s.client.share_connect(&unc, &s.user, s.pass.clone()).await.map_err(bad(StatusCode::BAD_GATEWAY))?;
        done.insert(share.to_string());
    }
    Ok(unc)
}

/// Keep a requested path inside the share: no "..", no drive letters.
fn clean(path: &str) -> Res<String> {
    let parts: Vec<&str> = path.split(['/', '\\']).filter(|p| !p.is_empty() && *p != ".").collect();
    if parts.iter().any(|p| *p == ".." || p.contains(':')) { return Err((StatusCode::BAD_REQUEST, "bad path".into())); }
    Ok(parts.join("\\"))
}

#[derive(Serialize)]
struct Entry { name: String, dir: bool, size: u64 }

async fn ls(State(app): State<Shared>, Query(q): Query<PathQ>) -> Res<Json<Vec<Entry>>> {
    let s = session(&app, &q.s).await?;
    let path = clean(&q.path)?;
    let mut out = Vec::new();
    if let Some(root) = &s.demo {
        let mut rd = tokio::fs::read_dir(root.join(path.replace('\\', "/"))).await.map_err(bad(StatusCode::NOT_FOUND))?;
        while let Ok(Some(e)) = rd.next_entry().await {
            let m = e.metadata().await.map_err(bad(StatusCode::BAD_GATEWAY))?;
            out.push(Entry { name: e.file_name().to_string_lossy().into(), dir: m.is_dir(), size: m.len() });
        }
        return Ok(Json(out));
    }
    let unc = share_ready(&s, &q.share).await?.with_path(&path);
    let dir = s.client.create_file(&unc, &FileCreateArgs::make_open_existing(DirAccessMask::new().with_list_directory(true).with_synchronize(true).into()))
        .await.map_err(bad(StatusCode::NOT_FOUND))?.unwrap_dir();
    let dir = Arc::new(dir);
    let mut stream = Directory::query::<FileDirectoryInformation>(&dir, "*").await.map_err(bad(StatusCode::BAD_GATEWAY))?;
    while let Some(item) = stream.next().await {
        let e = item.map_err(bad(StatusCode::BAD_GATEWAY))?;
        let name = e.file_name.to_string();
        if name == "." || name == ".." || e.file_attributes.hidden() || e.file_attributes.system() { continue; }
        out.push(Entry { name, dir: e.file_attributes.directory(), size: e.end_of_file });
    }
    drop(stream);
    dir.close().await.ok();
    Ok(Json(out))
}

enum Reader { Smb(smb::File), Local(tokio::sync::Mutex<tokio::fs::File>, u64) }
impl Reader {
    async fn len(&self) -> u64 { match self { Reader::Smb(f) => f.get_len().await.unwrap_or(0), Reader::Local(_, n) => *n } }
    async fn read_at(&self, buf: &mut [u8], off: u64) -> std::io::Result<usize> {
        match self {
            Reader::Smb(f) => f.read_at(buf, off).await.map_err(|e| std::io::Error::other(e.to_string())),
            Reader::Local(f, _) => { let mut f = f.lock().await; f.seek(std::io::SeekFrom::Start(off)).await?; f.read(buf).await }
        }
    }
}

fn mime_of(name: &str) -> &'static str {
    match Path::new(name).extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase()).as_deref() {
        Some("mp4") | Some("m4v") => "video/mp4", Some("webm") => "video/webm", Some("mkv") => "video/x-matroska", Some("mov") => "video/quicktime", Some("ogv") => "video/ogg",
        Some("mp3") => "audio/mpeg", Some("m4a") => "audio/mp4", Some("aac") => "audio/aac", Some("ogg") | Some("oga") | Some("opus") => "audio/ogg", Some("wav") => "audio/wav", Some("flac") => "audio/flac",
        _ => "application/octet-stream",
    }
}

fn range_of(h: &HeaderMap, len: u64) -> Option<(u64, u64)> {
    let v = h.get(header::RANGE)?.to_str().ok()?.strip_prefix("bytes=")?;
    let (a, b) = v.split_once('-')?;
    let (start, end) = if a.is_empty() { let n: u64 = b.parse().ok()?; (len.saturating_sub(n), len.saturating_sub(1)) }
        else { (a.parse().ok()?, if b.is_empty() { len.saturating_sub(1) } else { b.parse::<u64>().ok()?.min(len.saturating_sub(1)) }) };
    (start <= end && start < len).then_some((start, end))
}

async fn file(State(app): State<Shared>, Query(q): Query<PathQ>, headers: HeaderMap) -> Res<Response> {
    let s = session(&app, &q.s).await?;
    let path = clean(&q.path)?;
    if path.is_empty() { return Err((StatusCode::BAD_REQUEST, "no file".into())); }
    let reader = if let Some(root) = &s.demo {
        let f = tokio::fs::File::open(root.join(path.replace('\\', "/"))).await.map_err(bad(StatusCode::NOT_FOUND))?;
        let n = f.metadata().await.map(|m| m.len()).unwrap_or(0);
        Reader::Local(tokio::sync::Mutex::new(f), n)
    } else {
        let unc = share_ready(&s, &q.share).await?.with_path(&path);
        Reader::Smb(s.client.create_file(&unc, &FileCreateArgs::make_open_existing(FileAccessMask::new().with_generic_read(true)))
            .await.map_err(bad(StatusCode::NOT_FOUND))?.unwrap_file())
    };
    let len = reader.len().await;
    let (start, end, partial) = match headers.get(header::RANGE) {
        Some(_) => match range_of(&headers, len) { Some((a, b)) => (a, b, true), None => return Ok((StatusCode::RANGE_NOT_SATISFIABLE, [(header::CONTENT_RANGE, format!("bytes */{len}"))]).into_response()) },
        None => (0, len.saturating_sub(1), false),
    };
    let name = path.rsplit('\\').next().unwrap_or("file").to_string();
    let reader = Arc::new(reader);
    let total = if len == 0 { 0 } else { end - start + 1 };
    const CHUNK: usize = 1 << 20;
    let body = Body::from_stream(futures_util::stream::unfold((reader, start, total), |(r, pos, left)| async move {
        if left == 0 { return None; }
        let mut buf = vec![0u8; CHUNK.min(left as usize)];
        match r.read_at(&mut buf, pos).await {
            Ok(0) => None,
            Ok(n) => { buf.truncate(n); Some((Ok::<_, std::io::Error>(axum::body::Bytes::from(buf)), (r, pos + n as u64, left - n as u64))) }
            Err(e) => Some((Err(e), (r, pos, 0))),
        }
    }));
    let mut resp = Response::new(body);
    *resp.status_mut() = if partial { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK };
    let h = resp.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime_of(&name)));
    h.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    h.insert(header::CONTENT_LENGTH, HeaderValue::from(total));
    if partial { h.insert(header::CONTENT_RANGE, HeaderValue::from_str(&format!("bytes {start}-{end}/{len}")).unwrap()); }
    Ok(resp)
}
