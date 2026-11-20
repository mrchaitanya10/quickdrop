package in.theclabs.quickdrop;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(22,101,52), INK = Color.rgb(17,24,39), MUTED = Color.rgb(91,105,96);
    private LinearLayout content, setupCard, deviceCard;
    private TextView state, selectedLabel, deviceStatus, networkLabel;
    private Button choose, send, cancel, scan;
    private ProgressBar progress;
    private LocalTransferServer server;
    private LocalTransferServer.Source selected;
    private SharedPreferences preferences;
    private String trusted;
    private AlertDialog setupDialog;
    private NsdManager.RegistrationListener registration;
    private int advertisementGeneration;
    private int serverGeneration;
    private boolean paused, alive, sending, qrContactSeen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (!alive) return;
            if (server == null && !paused) startSharing();
            render(); handler.postDelayed(this, 1500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("quickdrop", MODE_PRIVATE);
        trusted = preferences.getString("trusted", null);
        getWindow().setStatusBarColor(Color.rgb(244,247,242));
        getWindow().setNavigationBarColor(Color.rgb(244,247,242));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22),dp(30),dp(22),dp(28)); content.setBackgroundColor(Color.rgb(244,247,242));
        scroll.addView(content); setContentView(scroll);
        content.setOnApplyWindowInsetsListener((view,insets) -> {
            content.setPadding(dp(22),dp(20)+insets.getSystemWindowInsetTop(),dp(22),dp(22)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.wordmark); logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setContentDescription("QuickDrop"); header.addView(logo,new LinearLayout.LayoutParams(0,dp(62),1));
        Button settings = new Button(this); settings.setText("Settings"); settings.setAllCaps(false);
        settings.setTextColor(GREEN); settings.setBackgroundColor(Color.TRANSPARENT);
        header.addView(settings,new LinearLayout.LayoutParams(-2,dp(52))); content.addView(header);
        settings.setOnClickListener(this::settingsMenu);
        label(content,"Scan. Connect. Send.",30,true,INK);
        label(content,"Scan the laptop QR to connect.",16,false,MUTED);
        setupCard = card();
        label(setupCard,"Connect your Chromebook",21,true,INK);
        label(setupCard,"Show the QR code in QuickDrop on your laptop, then scan it here.",15,false,MUTED);
        scan = button(setupCard,"Scan laptop QR",true);
        scan.setOnClickListener(view -> scanLaptop());
        state = label(content,"",15,true,GREEN); state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        deviceCard = card();
        label(deviceCard,"YOUR LAPTOP",12,true,MUTED);
        label(deviceCard,"Chromebook",23,true,INK);
        deviceStatus = label(deviceCard,"Waiting for your Chromebook…",15,false,MUTED);
        LinearLayout fileCard = card();
        label(fileCard,"WHAT WOULD YOU LIKE TO SEND?",12,true,MUTED);
        selectedLabel = label(fileCard,"Photos, videos, documents — up to 10 GB.",16,false,MUTED);
        selectedLabel.setMaxLines(3); selectedLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        choose = button(fileCard,"Choose a file",false);
        choose.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent,10);
        });
        send = button(content,"Send to Chromebook",true); send.setEnabled(false);
        send.setOnClickListener(view -> {
            if (server == null || trusted == null || selected == null || !server.online(trusted)) return;
            sending = true; server.selectFor(selected,trusted); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
            state.setText("Sending to your Chromebook…"); render();
        });
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1,dp(8)); barParams.topMargin=dp(18); content.addView(progress,barParams);
        cancel = button(content,"Cancel transfer",false); cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(view -> {
            if (server != null) server.cancelOffer(); sending=false; progress.setVisibility(View.GONE);
            state.setText("Transfer cancelled. Your original file is unchanged."); render();
        });
        networkLabel = label(content,"Keep both apps open. You can minimize the receiver on your Chromebook.",13,false,MUTED);
        label(content,"Private Wi-Fi or hotspot only · About & privacy in Settings",12,false,MUTED);
        alive = true; startSharing(); render(); handleShare(getIntent());
        // Open the camera once per fresh launcher start, not when returning
        // from scanning, a permission dialog, or the system file picker.
        if(saved==null && getIntent()!=null && Intent.ACTION_MAIN.equals(getIntent().getAction()))
            handler.post(()->{if(alive && !isFinishing())scanLaptop();});
    }
    private void render() {
        if (setupCard == null) return;
        setupCard.setVisibility(View.VISIBLE);
        scan.setEnabled(!sending);
        deviceCard.setVisibility(trusted == null ? View.GONE : View.VISIBLE);
        boolean online = server != null && server.online(trusted);
        if (sending) deviceStatus.setText("Receiving your file…");
        else if (paused) deviceStatus.setText("Sharing paused. Resume in Settings.");
        else deviceStatus.setText(online ? "Connected · ready to receive" : "Open QuickDrop Receiver on your Chromebook.");
        deviceStatus.setTextColor(online || sending ? GREEN : MUTED);
        send.setEnabled(selected != null && trusted != null && online && !sending && registration==null);
        send.setAlpha(send.isEnabled() ? 1f : .45f);
        send.setText(sending ? "Sending…" : "Send to Chromebook");
        choose.setEnabled(!sending); cancel.setVisibility(sending ? View.VISIBLE : View.GONE);
        networkLabel.setText(server == null && !paused ? "Connect to Wi-Fi or turn on your hotspot. Open Network settings from Settings." :
            "Keep both apps open. Mobile data is not needed for local files.");
    }
    private void settingsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this,anchor);
        String[] options = {"Connect a laptop","Network settings",paused?"Resume sharing":"Pause sharing","Forget Chromebook","About & privacy","Manual connection"};
        for (int i=0;i<options.length;i++) menu.getMenu().add(0,i,i,options[i]);
        menu.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()) {
                case 0: if(sending) state.setText("Finish or cancel the current transfer first."); else scanLaptop(); break;
                case 1:
                    try { startActivity(new Intent("android.settings.TETHER_SETTINGS")); }
                    catch(Exception unavailable) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } break;
                case 2: paused=!paused; if(paused) { stopSharing(); sending=false; state.setText("Sharing paused."); } else startSharing(); render(); break;
                case 3:
                    new AlertDialog.Builder(this).setTitle("Forget Chromebook?")
                        .setMessage("This removes its access. You can connect it again anytime.")
                        .setPositiveButton("Forget",(dialog,which) -> {
                            preferences.edit().remove("trusted").apply(); trusted=null; sending=false;
                            stopSharing(); paused=false; startSharing(); state.setText("Chromebook forgotten."); render();
                        }).setNegativeButton("Keep",null).show(); break;
                case 4:
                    new AlertDialog.Builder(this).setTitle("QuickDrop · C Labs")
                        .setMessage("Version 0.5.2 preview\n\nQR scanning happens on this phone. Camera frames stay on the device. Files travel directly over your local network. There is no cloud upload.\n\nThis preview uses unencrypted HTTP. Pairing approval controls access, but does not encrypt traffic. Use a trusted, password-protected private network.\n\nKeep the phone app open during transfers. Large files take longer; speed depends on your devices and Wi-Fi.")
                        .setPositiveButton("Got it",null).show(); break;
                case 5: if(!sending)connectLaptop(); break;
            } return true;
        }); menu.show();
    }
    private void scanLaptop() {
        if(sending){state.setText("Finish or cancel the current transfer first.");return;}
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            if(preferences.getBoolean("cameraRequested",false) && !shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)){
                new AlertDialog.Builder(this).setTitle("Allow camera access")
                    .setMessage("Enable Camera for QuickDrop in app settings to scan your laptop’s QR code.")
                    .setPositiveButton("Open app settings",(dialog,which)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
                    .setNegativeButton("Back",null).show();return;
            }
            preferences.edit().putBoolean("cameraRequested",true).apply();
            requestPermissions(new String[]{android.Manifest.permission.CAMERA},21);return;
        }
        if(registration!=null){stopAdvertisement();if(server!=null)server.closeQr();}
        startActivityForResult(new Intent(this,ScanActivity.class),20);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==21 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)scanLaptop();
        else if(request==21)state.setText("Camera access is needed to scan the laptop QR. You can also use Manual connection in Settings.");
    }
    private void scannedLaptop(String payload) {
        try {
            String ticket=QrHandshake.ticket(payload);paused=false;startSharing();
            if(server==null){state.setText("Connect both devices to the same private Wi-Fi or hotspot, then scan again.");return;}
            stopAdvertisement();qrContactSeen=false;String id=server.beginQr(ticket);
            NsdServiceInfo service=new NsdServiceInfo();service.setServiceName("QuickDrop-"+id);service.setServiceType("_quickdrop._tcp.");service.setPort(server.port());service.setAttribute("v","1");
            List<String> hosts=localAddresses();
            service.setAttribute("hosts",String.join(",",hosts.subList(0,Math.min(4,hosts.size()))));
            final int generation=advertisementGeneration;
            NsdManager manager=(NsdManager)getSystemService(NSD_SERVICE);
            registration=new NsdManager.RegistrationListener(){
                public void onServiceRegistered(NsdServiceInfo info){
                    if(!alive || generation!=advertisementGeneration){try{manager.unregisterService(this);}catch(Exception ignored){}return;}
                    runOnUiThread(()->{
                    if(alive && generation==advertisementGeneration)state.setText("QR scanned. Waiting for the laptop to find this phone…");
                });}
                public void onRegistrationFailed(NsdServiceInfo info,int error){runOnUiThread(()->{if(generation==advertisementGeneration){stopAdvertisement();if(server!=null)server.closeQr();state.setText("Local discovery couldn’t start. Retry the scan or use Manual connection in Settings.");}});}
                public void onServiceUnregistered(NsdServiceInfo info){}
                public void onUnregistrationFailed(NsdServiceInfo info,int error){}
            };
            manager.registerService(service,NsdManager.PROTOCOL_DNS_SD,registration);
            state.setText("QR scanned. Looking for your laptop on the local network…");
            handler.postDelayed(()->{
                if(generation==advertisementGeneration && registration!=null && !qrContactSeen)
                    state.setText("Still waiting for the laptop. Check both devices are on the same private Wi-Fi or hotspot and keep the laptop QR window open.");
            },7_000L);
            handler.postDelayed(()->{
                if(generation==advertisementGeneration && registration!=null){
                    stopAdvertisement();if(server!=null)server.closeQr();
                    state.setText(qrContactSeen ? "QR verification did not finish. Refresh the laptop QR and scan again." :
                        "The laptop did not reach this phone. Check the shared network, then refresh the laptop QR and scan again. Some hotspots or guest networks block discovery.");
                }
            },20_000L);
        }catch(Exception error){stopAdvertisement();state.setText("Open QuickDrop on the laptop and scan its current QR code.");}
    }
    private void stopAdvertisement() {
        advertisementGeneration++;
        if(registration!=null){try{((NsdManager)getSystemService(NSD_SERVICE)).unregisterService(registration);}catch(Exception ignored){}registration=null;}
    }
    private void connectLaptop() {
        paused=false; startSharing();
        if(server==null) { state.setText("Turn on your hotspot or connect to Wi-Fi, then try again."); return; }
        server.openPairing();
        try {
            List<String> ips=localAddresses();
            if(ips.isEmpty()) { state.setText("Connect to Wi-Fi or turn on your hotspot first."); return; }
            StringBuilder values=new StringBuilder(); for(String ip:ips) values.append(ip).append('\n');
            TextView message = new TextView(this); message.setPadding(dp(24),dp(16),dp(24),dp(16)); message.setTextSize(17); message.setTextColor(INK); message.setTextIsSelectable(true);
            message.setText("1. Open QuickDrop Receiver on your Chromebook.\n\n2. Enter this phone address:\n\n"+values.toString().trim()+
                "\n\n3. Click Connect phone, then compare and approve the code here.\n\nRequests are allowed for 2 minutes. Use the same private Wi-Fi or hotspot.");
            setupDialog = new AlertDialog.Builder(this).setTitle("Connect your Chromebook").setView(message)
                .setPositiveButton("Done",(dialog,which)->{if(server!=null)server.closePairing();})
                .setOnCancelListener(dialog->{if(server!=null)server.closePairing();}).create();
            setupDialog.show();
        } catch(Exception error) { state.setText("Couldn’t read your network address. Check your hotspot and retry."); }
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout card() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(15),dp(18),dp(18));
        GradientDrawable background=new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(20)); background.setStroke(dp(1),Color.rgb(220,229,218)); box.setBackground(background);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(16); content.addView(box,params); return box;
    }
    private TextView label(LinearLayout parent,String value,int size,boolean bold,int color) {
        TextView text=new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        if(bold)text.setTypeface(null,Typeface.BOLD); text.setPadding(0,dp(6),0,dp(6)); parent.addView(text,new LinearLayout.LayoutParams(-1,-2)); return text;
    }
    private Button button(LinearLayout parent,String value,boolean primary) {
        Button button=new Button(this); button.setText(value); button.setAllCaps(false); button.setTextSize(16);
        button.setTextColor(primary?Color.WHITE:GREEN); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?GREEN:Color.rgb(234,241,229)));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(56)); params.topMargin=dp(10); parent.addView(button,params); return button;
    }
    private void handleShare(Intent intent) {
        if(intent!=null && Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(uri!=null && "content".equals(uri.getScheme())) onActivityResult(10,RESULT_OK,new Intent().setData(uri));
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(intent!=null && Intent.ACTION_MAIN.equals(intent.getAction()))scanLaptop();
        else handleShare(intent);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20){if(result==RESULT_OK && data!=null)scannedLaptop(data.getStringExtra("quickdrop_qr"));return;}
        if(request!=10 || result!=RESULT_OK || data==null || data.getData()==null)return;
        if(sending) { state.setText("Finish or cancel the current transfer before choosing another file."); return; }
        final Uri uri=data.getData(); String name="quickdrop-file"; long size=-1;
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst()) { name=cursor.getString(0); if(!cursor.isNull(1))size=cursor.getLong(1); }
        } catch(Exception error) { state.setText("Couldn’t open that file. Choose one saved on your phone."); return; }
        if(size<1 || size>LocalTransferServer.MAX_SIZE) { state.setText("Choose a non-empty file up to 10 GB, saved on your phone."); return; }
        final String filename=name==null?"quickdrop-file":name.replace('\n',' ').replace('\r',' '); final long length=size;
        selected=new LocalTransferServer.Source() {
            public String name(){return filename;} public long size(){return length;}
            public InputStream open()throws IOException {
                InputStream input=getContentResolver().openInputStream(uri); if(input==null)throw new IOException("File unavailable"); return input;
            }
        };
        selectedLabel.setText(filename+"\n"+formatSize(size)); selectedLabel.setTextColor(INK); choose.setText("Change file");
        progress.setVisibility(View.GONE); state.setText(trusted==null?"Connect your Chromebook to send this file.":"Ready. Tap Send to Chromebook."); render();
    }
    private String formatSize(long size) { return size>=1_000_000_000L?String.format(Locale.ROOT,"%.2f GB",size/1e9):size>=1_000_000?String.format(Locale.ROOT,"%.1f MB",size/1e6):String.format(Locale.ROOT,"%.1f KB",size/1e3); }
    private List<String> localAddresses()throws Exception {
        List<String> ips=new ArrayList<>(); Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces!=null && interfaces.hasMoreElements()) {
            NetworkInterface adapter=interfaces.nextElement(); String name=adapter.getName();
            if(!adapter.isUp() || adapter.isLoopback() || name.matches("(?i).*(rmnet|ccmni|pdp|clat).*"))continue;
            Enumeration<InetAddress> list=adapter.getInetAddresses();
            while(list.hasMoreElements()) { InetAddress ip=list.nextElement(); if(ip instanceof Inet4Address && ip.isSiteLocalAddress() && !ips.contains(ip.getHostAddress()))ips.add(ip.getHostAddress()); }
        } return ips;
    }
    private void startSharing() {
        if(server!=null || paused)return;
        try {
            if(localAddresses().isEmpty())return;
            byte[] page;
            try(InputStream input=getAssets().open("receiver.html");ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);page=output.toByteArray();
            }
            final int generation=++serverGeneration;
            server=new LocalTransferServer(page,new LocalTransferServer.Listener() {
                public void message(String value) { runOnUiThread(()->{ if(alive && generation==serverGeneration && sending)state.setText(value); }); }
                public void progress(long sent,long total) { runOnUiThread(()->{if(alive && generation==serverGeneration && sending){progress.setProgress((int)(sent*100/total));state.setText(sent==total?"Saving on your Chromebook…":"Sending · "+(sent*100/total)+"%");}}); }
                public void received(String name) { runOnUiThread(()->{if(alive && generation==serverGeneration){sending=false;progress.setProgress(100);state.setText("Saved on your Chromebook. All done!");render();}}); }
                public void qrContact(){runOnUiThread(()->{
                    if(alive && generation==serverGeneration && registration!=null){qrContactSeen=true;state.setText("Laptop reached this phone. Verifying the scanned QR…");}
                });}
                public void paired(String id) {runOnUiThread(()->{
                    if(!alive || server==null || generation!=serverGeneration)return;
                    trusted=id;preferences.edit().putString("trusted",id).apply();stopAdvertisement();
                    state.setText("Connected to your Chromebook. Choose a file to send.");render();
                });}
                public void approve(String id,String code) { runOnUiThread(()->{
                    if(!alive || server==null || generation!=serverGeneration)return;
                    new AlertDialog.Builder(MainActivity.this).setTitle("Do these codes match?").setMessage(code+"\n\nCompare with your Chromebook. Connect only if both screens show this code.")
                        .setPositiveButton("Yes, connect",(dialog,which)->{
                            if(server==null || generation!=serverGeneration)return;
                            if(server.remembered(id)) {
                                if(trusted!=null && !trusted.equals(id))server.approve(trusted,false);
                                trusted=id;preferences.edit().putString("trusted",id).apply();
                            }
                            server.approve(id,true);server.closePairing();
                            if(setupDialog!=null) {setupDialog.dismiss();setupDialog=null;}
                            state.setText("Connected. Choose a file to send.");render();
                        }).setNegativeButton("Reject",(dialog,which)->{if(server!=null)server.approve(id,false);})
                        .setOnCancelListener(dialog->{if(server!=null)server.approve(id,false);}).show();
                }); }
            },45085,trusted);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch(Exception error) { state.setText("Couldn’t start sharing. Check your connection, then retry from Settings."); }
    }
    private void stopSharing() {
        serverGeneration++;
        stopAdvertisement();
        if(server!=null)try{server.close();}catch(IOException ignored){} server=null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onResume() { super.onResume(); if(alive) {handler.removeCallbacks(refresh);handler.post(refresh);} }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { alive=false;handler.removeCallbacks(refresh);stopSharing();super.onDestroy(); }
}
