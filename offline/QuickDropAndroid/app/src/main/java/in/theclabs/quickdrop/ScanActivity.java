package in.theclabs.quickdrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-device QR decoding. Camera frames never leave this activity. */
@SuppressWarnings("deprecation")
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private SurfaceView preview;
    private TextView status;
    private Camera camera;
    private int width,height;
    private volatile boolean active;
    private long lastAttempt;
    private final AtomicBoolean decoding=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(28),dp(20),dp(24));body.setBackgroundColor(Color.rgb(17,24,39));
        body.setOnApplyWindowInsetsListener((view,insets)->{body.setPadding(dp(20),dp(18)+insets.getSystemWindowInsetTop(),dp(20),dp(18)+insets.getSystemWindowInsetBottom());return insets;});
        TextView title=new TextView(this);title.setText("Scan your laptop’s QR");title.setTextColor(Color.WHITE);title.setTextSize(24);title.setPadding(8,8,8,16);body.addView(title);
        FrameLayout view=new FrameLayout(this);preview=new SurfaceView(this);preview.getHolder().addCallback(this);view.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        View frame=new View(this);GradientDrawable border=new GradientDrawable();border.setColor(Color.TRANSPARENT);border.setStroke(4,Color.rgb(134,239,172));border.setCornerRadius(18);frame.setBackground(border);
        int edge=(int)(240*getResources().getDisplayMetrics().density);FrameLayout.LayoutParams marker=new FrameLayout.LayoutParams(edge,edge,Gravity.CENTER);view.addView(frame,marker);
        body.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setText("Open QuickDrop on your laptop and point this camera at its QR code. Both devices need the same private Wi-Fi or hotspot.");status.setTextColor(Color.WHITE);status.setTextSize(16);status.setPadding(8,18,8,16);body.addView(status);
        Button back=new Button(this);back.setText("Back");back.setAllCaps(false);back.setOnClickListener(v->finish());body.addView(back,new LinearLayout.LayoutParams(-1,-2));
        setContentView(body);
    }
    @Override protected void onResume(){super.onResume();active=true;startCamera();}
    @Override protected void onPause(){active=false;closeCamera();super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    public void surfaceCreated(SurfaceHolder holder){startCamera();}
    public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){startCamera();}
    public void surfaceDestroyed(SurfaceHolder holder){closeCamera();}
    private void startCamera() {
        if(!active || camera!=null || preview==null || !preview.getHolder().getSurface().isValid())return;
        try {
            int id=0;Camera.CameraInfo info=new Camera.CameraInfo();
            for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}}
            Camera.getCameraInfo(id,info);camera=Camera.open(id);Camera.Parameters params=camera.getParameters();
            Camera.Size best=params.getSupportedPreviewSizes().get(0);
            for(Camera.Size size:params.getSupportedPreviewSizes())if(Math.abs(size.width-640)+Math.abs(size.height-480)<Math.abs(best.width-640)+Math.abs(best.height-480))best=size;
            width=best.width;height=best.height;params.setPreviewSize(width,height);params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> focus=params.getSupportedFocusModes();if(focus!=null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(params);
            int rotation=getWindowManager().getDefaultDisplay().getRotation();int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            int orientation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(info.orientation+degrees)%360)%360:(info.orientation-degrees+360)%360;
            camera.setDisplayOrientation(orientation);camera.setPreviewDisplay(preview.getHolder());camera.setPreviewCallback(this);camera.startPreview();
        }catch(Exception error){closeCamera();status.setText("Couldn’t open the camera. Allow Camera access for QuickDrop, then try scanning again.");}
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void closeCamera(){Camera old=camera;camera=null;if(old!=null){try{old.setPreviewCallback(null);old.stopPreview();}catch(Exception ignored){}try{old.release();}catch(Exception ignored){}}}
    public void onPreviewFrame(byte[] data,Camera source) {
        long now=SystemClock.elapsedRealtime();
        if(!active || now-lastAttempt<350 || !decoding.compareAndSet(false,true))return;
        lastAttempt=now;final int w=width,h=height;
        if(data==null || data.length<w*h){decoding.set(false);return;}
        final byte[] frame=Arrays.copyOf(data,w*h);
        try{worker.execute(()->{
            try {
                PlanarYUVLuminanceSource luminance=new PlanarYUVLuminanceSource(frame,w,h,0,0,w,h,false);
                Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
                Result result=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(luminance)),hints);
                QrHandshake.ticket(result.getText());
                runOnUiThread(()->{if(active && !isFinishing()){active=false;setResult(RESULT_OK,new Intent().putExtra("quickdrop_qr",result.getText()));finish();}});
            }catch(ReaderException ignored){}
            catch(IllegalArgumentException invalid){runOnUiThread(()->status.setText("Scan the QR code shown inside QuickDrop on your laptop."));}
            finally{decoding.set(false);}
        });}catch(RejectedExecutionException stopped){decoding.set(false);}
    }
}
