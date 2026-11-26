import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * NewPipeExtractor is built for newer Java than old Android has. Some of the Java methods it calls don't exist before Android 13
 * (API 33) and are not covered by the Android Gradle Plugin's desugaring. On a phone with Android 7 (a Fire TV Stick, say) they
 * crash the app the first time they run. This rewrites those calls, inside NewPipeExtractor only, to the same-named functions in
 * `app.kinora.compat.Compat`, which use methods that every Android version has.
 *
 * To support another method: add it to [REWRITES] and to `Compat`.
 */
abstract class CompatRewriteFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean = classData.className.startsWith("org.schabi.newpipe.")

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        val replacement = if (opcode == Opcodes.INVOKESTATIC) REWRITES["$owner.$name$descriptor"] else null
                        if (replacement != null) super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, replacement, descriptor, false)
                        else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }

    private companion object {
        const val COMPAT = "app/kinora/compat/Compat"
        const val CS = "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
        val REWRITES = mapOf(
            "java/net/URLEncoder.encode$CS" to "urlEncode", // Java 10, Android 13+
            "java/net/URLDecoder.decode$CS" to "urlDecode", // Java 10, Android 13+
        )
    }
}
