package com.tencent.trtc.mediashare.helper;

import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageView.ScaleType;

import androidx.annotation.RequiresApi;

import com.tencent.trtc.TRTCCloudDef;
import com.tencent.trtc.TRTCCloudListener;
import com.tencent.trtc.mediashare.helper.basic.Size;
import com.tencent.trtc.mediashare.helper.render.EglCore;
import com.tencent.trtc.mediashare.helper.render.opengl.GPUImageFilter;
import com.tencent.trtc.mediashare.helper.render.opengl.GpuImageI420Filter;
import com.tencent.trtc.mediashare.helper.render.opengl.OpenGlUtils;
import com.tencent.trtc.mediashare.helper.render.opengl.Rotation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * Custom rendering auxiliary class for live sharing of local media files,
 * which can help developers quickly implement TRTC custom rendering related functions
 * Mainly includes:
 * - Customized rendering of local preview video frames/remote user video frames;
 * - Mixed playback of local audio/remote audio;
 * ## Video frame rendering process
 * Video frame rendering uses texture, which is the openGL texture solution.
 * This is the best-performing video processing solution under the Android system. The specific process is as follows:
 * 1. Constructor:
 * A {@link android.os.HandlerThread} thread will be created,and all OpenGL operations will be performed in this thread.
 * 2. start():
 * Pass in a system TextureView (this View needs to be added to the activity's control tree) to display
 * the rendering results.
 * 3. onSurfaceTextureAvailable():
 * TextureView’s SurfaceTexture is ready, connect SurfaceTexture with
 * EGLContext (can be null) in {@link com.tencent.trtc.TRTCCloudDef.TRTCVideoFrame#texture} as a parameter,
 * Generate a new EGLContext, and SurfaceTexture will also be used as the rendering target of this EGLContext.
 * 4. onRenderVideoFrame():
 * SDK video frame callback, in which you can get the video texture ID and corresponding EGLContext.
 * Create a new EGLContext using this EGLContext as a parameter,
 * so that the new EGLContext can access the texture returned by the SDK.
 * Then a rendering message will be sent to HandlerThread to render the obtained video texture.
 * 5. renderInternal():
 * The specific rendering process of the HandlerThread thread,rendering the video texture to TextureView.
 * ## Audio frame playback process
 * Audio frames are played using AudioTrack, and the overall process is relatively simple:
 * 1. onMixedAllAudioFrame():
 * SDK data callback after mixing all audio data (including collecting audio data and all playing audio data)
 * In this callback, you can get the data information of the audio frame and use AudioTrack to play it;
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
public class CustomFrameRender
        implements TRTCCloudListener.TRTCVideoRenderListener, TRTCCloudListener.TRTCAudioFrameListener,
        Handler.Callback {
    public static final String TAG = "TestRenderVideoFrame";

    private static final int MSG_RENDER          = 2;
    private static final int MSG_DESTROY         = 3;
    private static final int RENDER_TYPE_TEXTURE = 0;
    private static final int RENDER_TYPE_I420    = 1;
    private static final int MSG_PLAY_AUDIO      = 5;

    private       int                mRenderType     = RENDER_TYPE_TEXTURE;
    private       int                mSteamType;
    private       String             mUserId;
    private       Size               mSurfaceSize    = new Size();
    private       Size               mLastInputSize  = new Size();
    private       Size               mLastOutputSize = new Size();
    private final HandlerThread      mGLThread;
    private final GLHandler          mGLHandler;
    private final FloatBuffer        mGLCubeBuffer;
    private final FloatBuffer        mGLTextureBuffer;
    private       EglCore            mEglCore;
    private       SurfaceTexture     mSurfaceTexture;
    private       TextureView        mRenderView;
    private       GPUImageFilter     mNormalFilter;
    private       GpuImageI420Filter mYUVFilter;
    private       AudioTrack         mAudioTrack;

    @Override
    public void onRenderVideoFrame(String userId, int streamType, final TRTCCloudDef.TRTCVideoFrame frame) {
        if (!userId.equals(mUserId) || mSteamType != streamType) {
            return;
        }
        if (frame.texture != null) {
            GLES20.glFinish();
        }
        mGLHandler.obtainMessage(MSG_RENDER, frame).sendToTarget();
    }

    @Override
    public void onCapturedAudioFrame(TRTCCloudDef.TRTCAudioFrame trtcAudioFrame) {

    }

    @Override
    public void onLocalProcessedAudioFrame(TRTCCloudDef.TRTCAudioFrame audioFrame) {

    }

    @Override
    public void onRemoteUserAudioFrame(TRTCCloudDef.TRTCAudioFrame audioFrame, String s) {

    }

    @Override
    public void onMixedPlayAudioFrame(TRTCCloudDef.TRTCAudioFrame audioFrame) {

    }

    @Override
    public void onMixedAllAudioFrame(TRTCCloudDef.TRTCAudioFrame audioFrame) {
        if (audioFrame == null) {
            return;
        }
        mGLHandler.obtainMessage(MSG_PLAY_AUDIO, audioFrame).sendToTarget();
    }

    @Override
    public void onVoiceEarMonitorAudioFrame(TRTCCloudDef.TRTCAudioFrame trtcAudioFrame) {

    }

    public CustomFrameRender(String userId, int steamType) {
        mUserId = userId;
        mSteamType = steamType;
        mGLCubeBuffer =
                ByteBuffer.allocateDirect(OpenGlUtils.CUBE.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mGLCubeBuffer.put(OpenGlUtils.CUBE).position(0);

        mGLTextureBuffer = ByteBuffer.allocateDirect(OpenGlUtils.TEXTURE.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLTextureBuffer.put(OpenGlUtils.TEXTURE).position(0);

        mGLThread = new HandlerThread(TAG);
        mGLThread.start();
        mGLHandler = new GLHandler(mGLThread.getLooper(), this);
        Log.i(TAG, "TestRenderVideoFrame");
    }

    /**
     * Start custom rendering of Camera.
     *
     * @param videoView The view where the user displays the preview screen.
     */
    public void start(TextureView videoView) {
        if (videoView == null) {
            Log.w(TAG, "start error when render view is null");
            return;
        }
        Log.i(TAG, "start render");

        //Set the SurfaceTexture life cycle callback of TextureView to manage the creation and destruction of GLThread
        mRenderView = videoView;
        mSurfaceTexture = mRenderView.getSurfaceTexture();

        mRenderView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                //Save surfaceTexture for creating OpenGL thread
                mSurfaceTexture = surface;
                mSurfaceSize = new Size(width, height);
                Log.i(TAG, String.format("onSurfaceTextureAvailable width: %d, height: %d", width, height));
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                mSurfaceSize = new Size(width, height);
                Log.i(TAG, String.format("onSurfaceTextureSizeChanged width: %d, height: %d", width, height));
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                // The surface is released and rendering needs to be stopped.
                mSurfaceTexture = null;
                // Wait for the Runnable to finish executing before returning,
                // otherwise the GL thread will use an invalid SurfaceTexture
                mGLHandler.runAndWaitDone(new Runnable() {
                    @Override
                    public void run() {
                        uninitGlComponent();
                    }
                });
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    public void stop() {
        if (mRenderView != null) {
            mRenderView.setSurfaceTextureListener(null);
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack.release();
            mAudioTrack = null;
        }
        mGLHandler.obtainMessage(MSG_DESTROY).sendToTarget();
    }

    private void initGlComponent(Object eglContext) {
        if (mSurfaceTexture == null) {
            return;
        }

        try {
            if (eglContext instanceof javax.microedition.khronos.egl.EGLContext) {
                mEglCore = new EglCore((javax.microedition.khronos.egl.EGLContext) eglContext,
                        new Surface(mSurfaceTexture));
            } else {
                mEglCore = new EglCore((android.opengl.EGLContext) eglContext, new Surface(mSurfaceTexture));
            }
        } catch (Exception e) {
            Log.e(TAG, "create EglCore failed.", e);
            return;
        }

        mEglCore.makeCurrent();
        if (mRenderType == RENDER_TYPE_TEXTURE) {
            mNormalFilter = new GPUImageFilter();
            mNormalFilter.init();
        } else if (mRenderType == RENDER_TYPE_I420) {
            mYUVFilter = new GpuImageI420Filter();
            mYUVFilter.init();
        }
    }

    private void renderInternal(TRTCCloudDef.TRTCVideoFrame frame) {
        mRenderType = RENDER_TYPE_I420;
        if (frame.bufferType == TRTCCloudDef.TRTC_VIDEO_BUFFER_TYPE_TEXTURE) {
            mRenderType = RENDER_TYPE_TEXTURE;
        } else if (frame.pixelFormat == TRTCCloudDef.TRTC_VIDEO_PIXEL_FORMAT_I420
                && frame.bufferType == TRTCCloudDef.TRTC_VIDEO_BUFFER_TYPE_BYTE_ARRAY) {
            mRenderType = RENDER_TYPE_I420;
        } else {
            Log.w(TAG, "error video frame type");
            return;
        }

        if (mEglCore == null && mSurfaceTexture != null) {
            Object eglContext = null;
            if (frame.texture != null) {
                eglContext =
                        frame.texture.eglContext10 != null ? frame.texture.eglContext10 : frame.texture.eglContext14;
            }
            initGlComponent(eglContext);
        }

        if (mEglCore == null) {
            return;
        }

        if (mLastInputSize.width != frame.width || mLastInputSize.height != frame.height
                || mLastOutputSize.width != mSurfaceSize.width || mLastOutputSize.height != mSurfaceSize.height) {
            Pair<float[], float[]> cubeAndTextureBuffer = OpenGlUtils
                    .calcCubeAndTextureBuffer(ScaleType.CENTER, Rotation.ROTATION_180, true, frame.width, frame.height,
                            mSurfaceSize.width, mSurfaceSize.height);
            mGLCubeBuffer.clear();
            mGLCubeBuffer.put(cubeAndTextureBuffer.first);
            mGLTextureBuffer.clear();
            mGLTextureBuffer.put(cubeAndTextureBuffer.second);

            mLastInputSize = new Size(frame.width, frame.height);
            mLastOutputSize = new Size(mSurfaceSize.width, mSurfaceSize.height);
        }

        mEglCore.makeCurrent();
        GLES20.glViewport(0, 0, mSurfaceSize.width, mSurfaceSize.height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        GLES20.glClearColor(0, 0, 0, 1.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        if (mRenderType == RENDER_TYPE_TEXTURE) {
            mNormalFilter.onDraw(frame.texture.textureId, mGLCubeBuffer, mGLTextureBuffer);
        } else {
            mYUVFilter.loadYuvDataToTexture(frame.data, frame.width, frame.height);
            mYUVFilter.onDraw(OpenGlUtils.NO_TEXTURE, mGLCubeBuffer, mGLTextureBuffer);
        }
        mEglCore.swapBuffer();
    }

    private void uninitGlComponent() {
        if (mNormalFilter != null) {
            mNormalFilter.destroy();
            mNormalFilter = null;
        }
        if (mYUVFilter != null) {
            mYUVFilter.destroy();
            mYUVFilter = null;
        }
        if (mEglCore != null) {
            mEglCore.unmakeCurrent();
            mEglCore.destroy();
            mEglCore = null;
        }
    }

    private void destroyInternal() {
        uninitGlComponent();

        if (Build.VERSION.SDK_INT >= 18) {
            mGLHandler.getLooper().quitSafely();
        } else {
            mGLHandler.getLooper().quit();
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_RENDER:
                renderInternal((TRTCCloudDef.TRTCVideoFrame) msg.obj);
                break;
            case MSG_DESTROY:
                destroyInternal();
                break;
            case MSG_PLAY_AUDIO:
                playAudioFrame((TRTCCloudDef.TRTCAudioFrame) msg.obj);
                break;
            default:
                Log.e(TAG, "handleMessage wrong what : " + msg.what);
                break;
        }
        return false;
    }

    private void playAudioFrame(TRTCCloudDef.TRTCAudioFrame audioFrame) {
        if (mAudioTrack == null) {
            int channelConfig;
            if (audioFrame.channel == 1) {
                channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            } else if (audioFrame.channel == 2) {
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            } else {
                Log.e(TAG, "audioFrame channel [" + audioFrame.channel + "] is error !");
                return;
            }
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, audioFrame.sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioTrack.getMinBufferSize(audioFrame.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT),
                    AudioTrack.MODE_STREAM);
            mAudioTrack.play();
        }
        mAudioTrack.write(audioFrame.data, 0, audioFrame.data.length);
    }


    public static class GLHandler extends Handler {
        public GLHandler(Looper looper, Callback callback) {
            super(looper, callback);
        }

        public void runAndWaitDone(final Runnable runnable) {
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            post(new Runnable() {
                @Override
                public void run() {
                    runnable.run();
                    countDownLatch.countDown();
                }
            });

            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
