package com.example.yannick.olay1;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RecordActivity extends Activity implements OnClickListener {

    private final static String CLASS_LABEL = "RecordActivity";
    private final static String LOG_TAG = CLASS_LABEL;

    private String ffmpeg_link = "rtmp://rtmp-api.facebook.com:80/rtmp/141706516283085?ds=1&a=AaYvjHrd0iNYELpu";

    long startTime = 0;
    boolean recording = false;

    private FFmpegFrameRecorder recorder;

    private boolean isPreviewOn = false;

    private int sampleAudioRateInHz = 44100;
    private int frameRate = 30;

    private int imageWidth = 1280;
    private int imageHeight = 720;

    /* audio data getting thread */
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;
    volatile boolean runAudioThread = true;

    /* video data getting thread */
    private Camera cameraDevice;
    private CameraView cameraView;

    private Frame yuvImage = null;

    /* layout setting */
    private final int bg_screen_bx = 232;
    private final int bg_screen_by = 128;
    private final int bg_screen_width = 700;
    private final int bg_screen_height = 500;
    private final int bg_width = 1123;
    private final int bg_height = 715;
    private final int live_width = 640;
    private final int live_height = 480;
    private int screenWidth, screenHeight;
    private Button btnRecorderControl;

    Frame[] images;
    long[] timestamps;
    ShortBuffer[] samples;
    int imagesIndex, samplesIndex;

    String path;

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        setContentView(R.layout.activity_main);

        path = Environment.getExternalStorageDirectory().getAbsolutePath();
       // path = getBaseContext().getCacheDir().getAbsolutePath();

       // Log.e(CLASS_LABEL,Environment.getExternalStorageDirectory().getAbsolutePath());
        //Log.e(CLASS_LABEL,getBaseContext().getCacheDir().getAbsolutePath());

       // throw new RuntimeException();

        initLayout();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        recording = false;

        if (cameraView != null) {
            cameraView.stopPreview();
        }

        if(cameraDevice != null) {
            cameraDevice.stopPreview();
            cameraDevice.release();
            cameraDevice = null;
        }
    }

    private void initLayout() {

        /* get size of screen */
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        screenWidth = display.getWidth();
        screenHeight = display.getHeight();


        RelativeLayout topLayout = new RelativeLayout(this);
        setContentView(topLayout);

        LayoutInflater myInflate =  (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        LinearLayout preViewLayout = (LinearLayout) myInflate.inflate(R.layout.activity_main, null);

        RelativeLayout.LayoutParams layoutParam = new RelativeLayout.LayoutParams(screenWidth, screenHeight);
        topLayout.addView(preViewLayout, layoutParam);

        /* add control button: start and stop */
        btnRecorderControl = (Button) findViewById(R.id.recorder_control);
        btnRecorderControl.setText("Start");
        btnRecorderControl.setOnClickListener(this);

        /* add camera view */
        int display_width_d = (int) (1.0 * bg_screen_width * screenWidth / bg_width);
        int display_height_d = (int) (1.0 * bg_screen_height * screenHeight / bg_height);
        int prev_rw, prev_rh;
        if (1.0 * display_width_d / display_height_d > 1.0 * live_width / live_height) {
            prev_rh = display_height_d;
            prev_rw = (int) (1.0 * display_height_d * live_width / live_height);
        } else {
            prev_rw = display_width_d;
            prev_rh = (int) (1.0 * display_width_d * live_height / live_width);
        }
        layoutParam = new RelativeLayout.LayoutParams(prev_rw, prev_rh);
        layoutParam.topMargin = (int) (1.0 * bg_screen_by * screenHeight / bg_height);
        layoutParam.leftMargin = (int) (1.0 * bg_screen_bx * screenWidth / bg_width);

        cameraDevice = Camera.open();
        Log.i(LOG_TAG, "cameara open");
        cameraView = new CameraView(this, cameraDevice);
        topLayout.addView(cameraView, layoutParam);
        Log.i(LOG_TAG, "cameara preview start: OK");
    }

    FFmpegFrameFilter filter;

    //---------------------------------------
    // initialize ffmpeg_recorder
    //---------------------------------------
    private void initRecorder() {

        yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
        recorder = new FFmpegFrameRecorder(ffmpeg_link, imageWidth, imageHeight, 2);

          /*
            From https://www.facebook.com/facebookmedia/get-started/live

            Video Format:
            1) We accept video in maximum 720p (720 x 1280) resolution, at 30 frames per second. (or 1 key frame every 2 seconds).
            2) You must send an I-frame (keyframe) at least once every two seconds throughout the stream.
            3) Recommended max bit rate is 4000 Kbps.
            4) Titles must be less than 255 characters otherwise the stream will fail.
            5) The Live API accepts H264 encoded video and AAC encoded audio only.

            Advanced Settings
            6) Pixel Aspect Ratio: Square.
            7) Frame Types: Progressive Scan.
            8) Audio Sample Rate: 44.1 KHz.
            9) Audio Bitrate: 128 Kbps stereo.
            10) Bitrate Encoding: CBR.
        */

        // 1) Satisfied by imageWidth = 320, imageHeight = 240 as Frame size. Frame rate is set to 30 fps
        recorder.setFrameRate(frameRate);

        // 2) You must send an I-frame (keyframe) at least once every two seconds throughout the stream.
        // Key frame interval, in our case every 2 seconds -> 30 (fps) * 2 = 60
        // (gop length)
        recorder.setGopSize(60);

        // 3) Bitrate <  4000 Kbps
        recorder.setVideoBitrate(2000000);

        // 4) Title can not be set here, must be set in the publishing tool or while creating the live video over the API

        // 5) The Live API accepts H264 encoded video and AAC encoded audio only.
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);

        // 6) Pixel Aspect Ratio: Square. TODO! How should we set this?
        // Maybe over : recorder.setPixelFormat(); but with what parameter?

        // 7) Frame Types: Progressive Scan. TODO! How should we set this?

        // 8) Audio Sample Rate: 44.1 KHz.
        recorder.setSampleRate(44100);

        // 9) Audio Bitrate: 128 Kbps stereo.
        recorder.setAudioBitrate(128000);
        recorder.setAudioChannels(2);

        // 10) Bitrate Encoding: CBR. TODO How should we set this?

        /*
            Additional Settings from the orginal RecordActivity.java and  WebcamAndMicrophoneCapture.java (there the stream to rtmp)
         */

        // decrease "startup" latency in FFMPEG (see:
        // https://trac.ffmpeg.org/wiki/StreamingGuide)
        recorder.setVideoOption("tune", "zerolatency");

        // tradeoff between quality and encode speed
        // possible values are ultrafast,superfast, veryfast, faster, fast,
        // medium, slow, slower, veryslow
        // ultrafast offers us the least amount of compression (lower encoder
        // CPU) at the cost of a larger stream size
        // at the other end, veryslow provides the best compression (high
        // encoder CPU) while lowering the stream size
        // (see: https://trac.ffmpeg.org/wiki/Encode/H.264)
        recorder.setVideoOption("preset", "ultrafast");

        // Constant Rate Factor (see: https://trac.ffmpeg.org/wiki/Encode/H.264)
        recorder.setVideoOption("crf", "28");

        // 2000 kb/s, reasonable "sane" area for 720
        recorder.setFormat("flv");

        // We don't want variable bitrate audio
        recorder.setAudioOption("crf", "0");

        // TODO What is this for?
        recorder.setInterleaved(true);

        // We don't want variable bitrate audio
        recorder.setAudioOption("crf", "0");

        // Highest quality
        recorder.setAudioQuality(0);

        Log.i(LOG_TAG, "recorder initialize success");

        try{
            filter = new FFmpegFrameFilter("movie="+path+"/image.png [logo];[in][logo]overlay=0:0:1:format=rgb [out]",imageWidth, imageHeight);
            filter.start();
        }catch (FrameFilter.Exception e){
            Log.e(CLASS_LABEL,"Error while starting filter: "+e.getMessage());
            e.printStackTrace();
        }

        FFmpegLogCallback.set();

        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable);
        runAudioThread = true;
    }

    public void startRecording() {

        initRecorder();

        try {
            recorder.start();
            startTime = System.currentTimeMillis();
            recording = true;
            audioThread.start();

        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    public void stopRecording() {

        runAudioThread = false;
        try {
            audioThread.join();
        } catch (InterruptedException e) {
            // reset interrupt to be nice
            Thread.currentThread().interrupt();
            return;
        }
        audioRecordRunnable = null;
        audioThread = null;

        if (recorder != null && recording) {
            recording = false;
            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");
            try {
                recorder.stop();
                recorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            recorder = null;

        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (recording) {
                stopRecording();
            }

            finish();

            return true;
        }

        return super.onKeyDown(keyCode, event);
    }


    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = ShortBuffer.allocate(bufferSize);

            Log.d(LOG_TAG, "audioRecord.startRecording()");
            audioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (runAudioThread) {

                //Log.v(LOG_TAG,"recording? " + recording);
                bufferReadResult = audioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if (bufferReadResult > 0) {
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (recording) {
                        try {
                            recorder.recordSamples(audioData);
                            //Log.v(LOG_TAG,"recording " + 1024*i + " to " + 1024*i+1024);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG,e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(LOG_TAG,"AudioThread Finished, release audioRecord");

            /* encoding finish, release recorder */
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.v(LOG_TAG,"audioRecord released");
            }
        }
    }

    //---------------------------------------------
    // camera thread, gets and encodes video data
    //---------------------------------------------
    class CameraView extends SurfaceView implements SurfaceHolder.Callback, PreviewCallback {

        private SurfaceHolder mHolder;
        private Camera mCamera;

        public CameraView(Context context, Camera camera) {
            super(context);
            setWillNotDraw(false);
            Log.w("camera","camera view");
            mCamera = camera;
            mHolder = getHolder();
            mHolder.addCallback(CameraView.this);
            mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
            mCamera.setPreviewCallback(CameraView.this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
          /*   Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setTextSize(50);

            String s = "Test TEXT Player 1";
            float textWidth = paint.measureText(s);
            canvas.drawText(s, 0,0, paint);

            canvas.drawRect(0,0,200,200,paint);

           if (faces != null) {
                paint.setStrokeWidth(2);
                paint.setStyle(Paint.Style.STROKE);
                float scaleX = (float)getWidth()/grayImage.width();
                float scaleY = (float)getHeight()/grayImage.height();
                int total = faces.total();
                for (int i = 0; i < total; i++) {
                    CvRect r = new CvRect(cvGetSeqElem(faces, i));
                    int x = r.x(), y = r.y(), w = r.width(), h = r.height();
                    canvas.drawRect(x*scaleX, y*scaleY, (x+w)*scaleX, (y+h)*scaleY, paint);
                }
            }*/
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                stopPreview();
                mCamera.setPreviewDisplay(holder);
            } catch (IOException exception) {
                mCamera.release();
                mCamera = null;
            }
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            stopPreview();

            Camera.Parameters camParams = mCamera.getParameters();
            List<Camera.Size> sizes = camParams.getSupportedPreviewSizes();
            // Sort the list in ascending order
            Collections.sort(sizes, new Comparator<Camera.Size>() {

                public int compare(final Camera.Size a, final Camera.Size b) {
                    return a.width * a.height - b.width * b.height;
                }
            });

            // Pick the first preview size that is equal or bigger, or pick the last (biggest) option if we cannot
            // reach the initial settings of imageWidth/imageHeight.
            for (int i = 0; i < sizes.size(); i++) {
                if ((sizes.get(i).width >= imageWidth && sizes.get(i).height >= imageHeight) || i == sizes.size() - 1) {
                    imageWidth = sizes.get(i).width;
                    imageHeight = sizes.get(i).height;
                    Log.v(LOG_TAG, "Changed to supported resolution: " + imageWidth + "x" + imageHeight);
                    break;
                }
            }
            camParams.setPreviewSize(imageWidth, imageHeight);

            Log.v(LOG_TAG,"Setting imageWidth: " + imageWidth + " imageHeight: " + imageHeight + " frameRate: " + frameRate);

            camParams.setPreviewFrameRate(frameRate);

            mCamera.setParameters(camParams);

            // Set the holder (which might have changed) again
            try {
                mCamera.setPreviewDisplay(holder);
                mCamera.setPreviewCallback(CameraView.this);
                startPreview();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Could not set preview display in surfaceChanged");
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            try {
                mHolder.addCallback(null);
                mCamera.setPreviewCallback(null);
            } catch (RuntimeException e) {
                // The camera has probably just been released, ignore.
            }
        }

        public void startPreview() {
            if (!isPreviewOn && mCamera != null) {
                isPreviewOn = true;
                mCamera.startPreview();
            }
        }

        public void stopPreview() {
            if (isPreviewOn && mCamera != null) {
                isPreviewOn = false;
                mCamera.stopPreview();
            }
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                startTime = System.currentTimeMillis();
                return;
            }

            /* get video data */
            if (yuvImage != null && recording) {

                // (1) Inserting a little black rect into the camera frame:
              /*  opencv_core.Mat submat = yuvImage.submat(0, 100, 0, 100);
                opencv_core.Mat image =  new opencv_core.Mat(100, 100, yuvImage.type(), new opencv_core.Scalar(0,0,0));
                image.copyTo(submat);
*/

                ((ByteBuffer)yuvImage.image[0].position(0)).put(data);

                try{
                    filter.push(yuvImage);
                }catch (FrameFilter.Exception ex){
                    ex.printStackTrace();
                    Log.e(CLASS_LABEL,"Error while pushing filter: "+ex.getMessage());
                }

                try {
                    long t = 1000 * (System.currentTimeMillis() - startTime);
                    if (t > recorder.getTimestamp()) {
                        recorder.setTimestamp(t);
                    }
                    recorder.record(yuvImage);
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.v(LOG_TAG,e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onClick(View v) {
        System.out.println("ON CLICK Button");

        if (!recording) {
            startRecording();
            Log.w(LOG_TAG, "Start Button Pushed");
            btnRecorderControl.setText("Stop");
        } else {
            // This will trigger the audio recording loop to stop and then set isRecorderStart = false;
            stopRecording();
            Log.w(LOG_TAG, "Stop Button Pushed");
            btnRecorderControl.setText("Start");
        }
    }
}