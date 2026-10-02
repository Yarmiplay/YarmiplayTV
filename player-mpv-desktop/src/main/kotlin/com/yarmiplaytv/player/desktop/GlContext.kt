package com.yarmiplaytv.player.desktop

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.CGL
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/** An offscreen OpenGL context, current on the thread that created it. */
internal interface GlContext {
    fun getProcAddress(name: String): Long
    fun destroy()

    companion object {
        /** Creates a context and makes it current on the calling thread. */
        fun create(): GlContext {
            val os = System.getProperty("os.name").lowercase()
            val context = if (os.contains("mac")) CglContext() else GlfwContext()
            GL.createCapabilities()
            return context
        }
    }
}

/**
 * A hidden 1x1 GLFW window; we only render into our own framebuffer. GLFW officially wants its main thread,
 * but on Windows and X11 a dedicated thread works as long as every GLFW call happens on it.
 */
private class GlfwContext : GlContext {
    private val window: Long

    init {
        check(GLFW.glfwInit()) { "glfwInit failed" }
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        window = GLFW.glfwCreateWindow(1, 1, "SyncplayTV video", MemoryUtil.NULL, MemoryUtil.NULL)
        check(window != MemoryUtil.NULL) { "Couldn't create an OpenGL 3.3 context" }
        GLFW.glfwMakeContextCurrent(window)
        GLFW.glfwSwapInterval(0)
    }

    override fun getProcAddress(name: String): Long = GLFW.glfwGetProcAddress(name)

    override fun destroy() {
        GLFW.glfwMakeContextCurrent(MemoryUtil.NULL)
        GLFW.glfwDestroyWindow(window)
    }
}

/** A windowless CGL context (GLFW would need the main thread, which AWT owns on macOS). */
private class CglContext : GlContext {
    private val context: Long

    init {
        MemoryStack.stackPush().use { stack ->
            val attributes = stack.ints(
                CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                CGL.kCGLPFAAccelerated,
                CGL.kCGLPFAColorSize, 24,
                CGL.kCGLPFAAllowOfflineRenderers,
                0,
            )
            val pixelFormat = stack.mallocPointer(1)
            val count = stack.mallocInt(1)
            check(CGL.CGLChoosePixelFormat(attributes, pixelFormat, count) == 0 && pixelFormat[0] != 0L) { "No OpenGL 3.2 pixel format" }
            val ctx: PointerBuffer = stack.mallocPointer(1)
            check(CGL.CGLCreateContext(pixelFormat[0], MemoryUtil.NULL, ctx) == 0) { "CGLCreateContext failed" }
            CGL.CGLDestroyPixelFormat(pixelFormat[0])
            context = ctx[0]
            CGL.CGLSetCurrentContext(context)
        }
    }

    override fun getProcAddress(name: String): Long = GL.getFunctionProvider()?.getFunctionAddress(name) ?: 0L

    override fun destroy() {
        CGL.CGLSetCurrentContext(MemoryUtil.NULL)
        CGL.CGLDestroyContext(context)
    }
}
