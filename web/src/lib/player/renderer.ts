/**
 * Draws video frames on a canvas with WebGL2: each VideoFrame is uploaded as a texture (the browser converts it from
 * YUV) and drawn letterboxed to keep its shape. Falls back to a 2D canvas where WebGL2 isn't available.
 */

const VERTEX = `#version 300 es
in vec2 position;
out vec2 uv;
uniform vec4 box;
void main() {
  uv = vec2(position.x, 1.0 - position.y);
  gl_Position = vec4(box.xy + position * box.zw, 0.0, 1.0);
}`;

const FRAGMENT = `#version 300 es
precision highp float;
in vec2 uv;
out vec4 color;
uniform sampler2D frame;
void main() {
  color = vec4(texture(frame, uv).rgb, 1.0);
}`;

export class Renderer {
  private readonly gl: WebGL2RenderingContext | null;
  private readonly ctx2d: CanvasRenderingContext2D | null = null;
  private box: WebGLUniformLocation | null = null;
  private texture: WebGLTexture | null = null;
  /** The frame on screen, kept for redraws (a resize) and screenshots. */
  private current: VideoFrame | null = null;

  constructor(readonly canvas: HTMLCanvasElement) {
    this.gl = canvas.getContext("webgl2", { alpha: false, antialias: false, preserveDrawingBuffer: true, desynchronized: true });
    if (this.gl) this.setUp(this.gl);
    else this.ctx2d = canvas.getContext("2d");
  }

  private setUp(gl: WebGL2RenderingContext) {
    const compile = (type: number, source: string) => {
      const shader = gl.createShader(type)!;
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader) ?? "shader");
      return shader;
    };
    const program = gl.createProgram()!;
    gl.attachShader(program, compile(gl.VERTEX_SHADER, VERTEX));
    gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAGMENT));
    gl.linkProgram(program);
    gl.useProgram(program);
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
    const position = gl.getAttribLocation(program, "position");
    gl.enableVertexAttribArray(position);
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
    this.box = gl.getUniformLocation(program, "box");
    this.texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  }

  /** Keeps the canvas's pixels matching its size on screen. */
  resize(): boolean {
    const ratio = devicePixelRatio || 1;
    const width = Math.max(1, Math.round(this.canvas.clientWidth * ratio));
    const height = Math.max(1, Math.round(this.canvas.clientHeight * ratio));
    if (this.canvas.width === width && this.canvas.height === height) return false;
    this.canvas.width = width;
    this.canvas.height = height;
    return true;
  }

  /** Where a frame of [width]×[height] sits in the canvas, letterboxed: x, y, w, h in canvas pixels. */
  fit(width: number, height: number): [number, number, number, number] {
    const cw = this.canvas.width;
    const ch = this.canvas.height;
    const scale = Math.min(cw / width, ch / height);
    const w = width * scale;
    const h = height * scale;
    return [(cw - w) / 2, (ch - h) / 2, w, h];
  }

  /** Shows [frame] (the renderer keeps it until the next one). */
  draw(frame: VideoFrame) {
    if (this.current && this.current !== frame) this.current.close();
    this.current = frame;
    this.paint();
  }

  /** Paints the current frame again, as after a resize. */
  paint() {
    this.resize();
    const frame = this.current;
    const width = frame?.displayWidth ?? 0;
    const height = frame?.displayHeight ?? 0;
    if (this.gl) {
      const gl = this.gl;
      gl.viewport(0, 0, this.canvas.width, this.canvas.height);
      gl.clearColor(0, 0, 0, 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      if (!frame) return;
      gl.bindTexture(gl.TEXTURE_2D, this.texture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, frame);
      const [x, y, w, h] = this.fit(width, height);
      // The quad in clip space: origin at the bottom left of the box, size in clip units.
      const cw = this.canvas.width;
      const ch = this.canvas.height;
      gl.uniform4f(this.box, (x / cw) * 2 - 1, 1 - ((y + h) / ch) * 2, (w / cw) * 2, (h / ch) * 2);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    } else if (this.ctx2d) {
      this.ctx2d.fillStyle = "#000";
      this.ctx2d.fillRect(0, 0, this.canvas.width, this.canvas.height);
      if (!frame) return;
      const [x, y, w, h] = this.fit(width, height);
      this.ctx2d.drawImage(frame, x, y, w, h);
    }
  }

  /** The frame on screen at its own size, as a PNG. */
  async screenshot(): Promise<Blob | null> {
    const frame = this.current;
    if (!frame) return null;
    const canvas = new OffscreenCanvas(frame.displayWidth, frame.displayHeight);
    canvas.getContext("2d")!.drawImage(frame, 0, 0);
    return canvas.convertToBlob({ type: "image/png" });
  }

  get frameSize(): [number, number] | null {
    return this.current ? [this.current.displayWidth, this.current.displayHeight] : null;
  }

  clear() {
    this.current?.close();
    this.current = null;
    this.paint();
  }
}
