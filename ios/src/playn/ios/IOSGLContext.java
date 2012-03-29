/**
 * Copyright 2012 The PlayN Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package playn.ios;

import cli.System.Drawing.RectangleF;
import cli.System.IntPtr;
import cli.System.Runtime.InteropServices.Marshal;

import cli.MonoTouch.CoreGraphics.CGBitmapContext;
import cli.MonoTouch.CoreGraphics.CGImage;
import cli.MonoTouch.CoreGraphics.CGImageAlphaInfo;
import cli.MonoTouch.UIKit.UIDeviceOrientation;
import cli.MonoTouch.UIKit.UIImage;
import cli.OpenTK.Graphics.ES20.All;
import cli.OpenTK.Graphics.ES20.GL;

import playn.core.InternalTransform;
import playn.core.PlayN;
import playn.core.gl.GLContext;
import playn.core.gl.GLShader;
import playn.core.gl.GroupLayerGL;

class IOSGLContext extends GLContext
{
  public static final boolean CHECK_ERRORS = true;

  public int viewWidth, viewHeight;

  int fbufWidth, fbufHeight, orient;
  private int lastFrameBuffer;

  private GLShader.Texture texShader;
  private GLShader.Color colorShader;

  IOSGLContext(int screenWidth, int screenHeight) {
    fbufWidth = viewWidth = screenWidth;
    fbufHeight = viewHeight = screenHeight;
  }

  void init() {
    reinitGL();
  }

  @Override
  public Integer createFramebuffer(Object tex) {
    int[] fbufw = new int[1];
    GL.GenFramebuffers(1, fbufw);

    int fbuf = fbufw[0];
    GL.BindFramebuffer(All.wrap(All.Framebuffer), fbuf);
    GL.FramebufferTexture2D(All.wrap(All.Framebuffer), All.wrap(All.ColorAttachment0),
                            All.wrap(All.Texture2D), (Integer) tex, 0);

    return fbuf;
  }

  @Override
  public void deleteFramebuffer(Object fbuf) {
    GL.DeleteFramebuffers(1, new int[] { (Integer) fbuf });
  }

  @Override
  public void bindFramebuffer(Object fbuf, int width, int height) {
    bindFramebuffer((Integer)fbuf, width, height, false);
  }

  @Override
  public void bindFramebuffer() {
    bindFramebuffer(0, viewWidth, viewHeight, false);
  }

  @Override
  public Integer createTexture(boolean repeatX, boolean repeatY) {
    int[] texw = new int[1];
    GL.GenTextures(1, texw);
    int tex = texw[0];
    GL.BindTexture(All.wrap(All.Texture2D), tex);
    GL.TexParameter(All.wrap(All.Texture2D), All.wrap(All.TextureMinFilter), All.Linear);
    GL.TexParameter(All.wrap(All.Texture2D), All.wrap(All.TextureMagFilter), All.Linear);
    GL.TexParameter(All.wrap(All.Texture2D), All.wrap(All.TextureWrapS),
                    repeatX ? All.Repeat : All.ClampToEdge);
    GL.TexParameter(All.wrap(All.Texture2D), All.wrap(All.TextureWrapT),
                    repeatY ? All.Repeat : All.ClampToEdge);
    return tex;
  }

  @Override
  public Integer createTexture(int width, int height, boolean repeatX, boolean repeatY) {
    int tex = createTexture(repeatX, repeatY);
    GL.TexImage2D(All.wrap(All.Texture2D), 0, All.Rgba, width, height, 0, All.wrap(All.Rgba),
                  All.wrap(All.UnsignedByte), null);
    return tex;
  }

  @Override
  public void destroyTexture(Object texObj) {
    GL.DeleteTextures(1, new int[] { (Integer)texObj });
  }

  @Override
  public void startClipped(int x, int y, int width, int height) {
    flush(); // flush any pending unclipped calls
    switch (orient) {
    default:
    case UIDeviceOrientation.Portrait:
      GL.Scissor(x, fbufHeight-y-height, width, height);
      break;
    case UIDeviceOrientation.PortraitUpsideDown:
      GL.Scissor(x-width, fbufHeight-y, width, height);
      break;
    case UIDeviceOrientation.LandscapeLeft:
      GL.Scissor(x-width, fbufHeight-y-height, width, height);
      break;
    case UIDeviceOrientation.LandscapeRight:
      GL.Scissor(x, fbufHeight-y, width, height);
      break;
    }
    GL.Enable(All.wrap(All.ScissorTest));
  }

  @Override
  public void endClipped() {
    flush(); // flush our clipped calls with SCISSOR_TEST still enabled
    GL.Disable(All.wrap(All.ScissorTest));
  }

  @Override
  public void clear(float r, float g, float b, float a) {
    GL.ClearColor(r, g, b, a);
    GL.Clear(All.ColorBufferBit);
  }

  @Override
  public void checkGLError(String op) {
    if (CHECK_ERRORS) {
      All error;
      while (!(error = GL.GetError()).Equals(All.wrap(All.NoError))) {
        PlayN.log().error(op + ": glError " + error);
      }
    }
  }

  @Override
  protected GLShader.Texture quadTexShader() {
    return texShader;
  }
  @Override
  protected GLShader.Texture trisTexShader() {
    return texShader;
  }
  @Override
  protected GLShader.Color quadColorShader() {
    return colorShader;
  }
  @Override
  protected GLShader.Color trisColorShader() {
    return colorShader;
  }

  void bindFramebuffer(int frameBuffer, int width, int height, boolean force) {
    if (force || lastFrameBuffer != frameBuffer) {
      checkGLError("bindFramebuffer");
      flush();

      lastFrameBuffer = frameBuffer;
      GL.BindFramebuffer(All.wrap(All.Framebuffer), frameBuffer);
      GL.Viewport(0, 0, width, height);
      fbufWidth = width;
      fbufHeight = height;
    }
  }

  void updateTexture(int tex, UIImage image) {
    CGImage cimage = image.get_CGImage();
    int width = cimage.get_Width(), height = cimage.get_Height();
    if (width == 0 || height == 0) {
      PlayN.log().warn("Ignoring texture update for empty image (" + width + "x" + height + ").");
      return;
    }

    IntPtr data = Marshal.AllocHGlobal(width * height * 4);
    CGBitmapContext bctx = new CGBitmapContext(
      data, width, height, 8, 4 * width, IOSGraphics.colorSpace,
      CGImageAlphaInfo.wrap(CGImageAlphaInfo.PremultipliedLast));

    bctx.ClearRect(new RectangleF(0, 0, width, height));
    // bctx.TranslateCTM(0, height - imageSize.Height);
    bctx.DrawImage(new RectangleF(0, 0, width, height), cimage);

    updateTexture(tex, width, height, data);

    bctx.Dispose();
    Marshal.FreeHGlobal(data);
  }

  void updateTexture(int tex, int width, int height, IntPtr data) {
    GL.TexImage2D(All.wrap(All.Texture2D), 0, All.Rgba, width, height, 0, All.wrap(All.Rgba),
                  All.wrap(All.UnsignedByte), data);
  }

  void paintLayers(InternalTransform rootTransform, GroupLayerGL rootLayer) {
    checkGLError("updateLayers start");
    bindFramebuffer();
    GL.Clear(All.ColorBufferBit | All.DepthBufferBit); // clear to transparent
    rootLayer.paint(rootTransform, 1); // paint all the layers
    checkGLError("updateLayers end");
    useShader(null); // guarantee a flush
  }

  private void reinitGL() {
    GL.Disable(All.wrap(All.CullFace));
    GL.Enable(All.wrap(All.Blend));
    GL.BlendFunc(All.wrap(All.One), All.wrap(All.OneMinusSrcAlpha));
    GL.ClearColor(0, 0, 0, 1);
    texShader = new IOSGLShader.Texture(this);
    colorShader = new IOSGLShader.Color(this);
  }
}
