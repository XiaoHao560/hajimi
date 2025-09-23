package manbo.hajimi.cn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private EditText inputText, passwordEditText;
    private TextView outputText, charCount;
    private Button encryptBtn, decryptBtn, copyBtn, clearBtn, pasteBtn,
                   generateQrBtn, scanQrBtn, scanFromGalleryBtn, shareBtn;

    private SharedPreferences preferences;
    private static final String PREFS_NAME = "HakimiEncryptorPrefs";
    private static final String KEY_AUTO_CLEAR = "auto_clear";
    private static final String KEY_CLEAR_DELAY = "clear_delay";
    private static final int DEFAULT_CLEAR_DELAY = 30;
    private CountDownTimer clearClipboardTimer;
    private String lastCopiedText = "";

    private static final char[] CODE_CHARS = {
            '哈', '基', '米', '窝', '那', '没', '撸', '多',
            '阿', '西', '噶', '压', '库', '路', '曼', '波',
            '哦', '吗', '吉', '利', '咋', '酷', '友', '达',
            '喔', '哪', '买', '奈', '诺', '娜', '美', '嘎',
            '呀', '菇', '啊', '自', '一', '漫', '步', '耶',
            '哒', '我', '找', '咕', '马', '子', '砸', '不',
            '南', '北', '绿', '豆', '椰', '奶', '龙', '瓦',
            '塔', '尼', '莫', '欧', '季', '里', '得', '喵'
    };
    private static final Map<Character, Integer> CHAR_TO_INDEX = new HashMap<>();
    static { for (int i = 0; i < CODE_CHARS.length; i++) CHAR_TO_INDEX.put(CODE_CHARS[i], i); }
    private static final int BITS_PER_CHAR = 6;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    String scanned = result.getContents();
                    inputText.setText(scanned);
                    Toast.makeText(this, "二维码内容已导入", Toast.LENGTH_SHORT).show();
                    if (isHakimiEncryptedText(scanned)) autoDecrypt(scanned);
                }
            });

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    try {
                        Bitmap bmp = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                        String txt = decodeQRCodeFromBitmap(bmp);
                        if (txt != null) {
                            inputText.setText(txt);
                            if (isHakimiEncryptedText(txt)) autoDecrypt(txt);
                        } else {
                            Toast.makeText(this, "未识别到二维码", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        initViews();
        setupListeners();
    }

    private void initViews() {
        inputText        = findViewById(R.id.inputText);
        passwordEditText = findViewById(R.id.passwordEditText);
        outputText       = findViewById(R.id.outputText);
        charCount        = findViewById(R.id.charCount);
        encryptBtn       = findViewById(R.id.encryptBtn);
        decryptBtn       = findViewById(R.id.decryptBtn);
        copyBtn          = findViewById(R.id.copyBtn);
        clearBtn         = findViewById(R.id.clearBtn);
        pasteBtn         = findViewById(R.id.pasteBtn);
        generateQrBtn    = findViewById(R.id.generateQrBtn);
        scanQrBtn        = findViewById(R.id.scanQrBtn);
        scanFromGalleryBtn = findViewById(R.id.scanFromGalleryBtn);
        shareBtn         = findViewById(R.id.shareBtn);
    }

    private void setupListeners() {
        inputText.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) { updateCharCount(); }
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){}
        });

        encryptBtn.setOnClickListener(v -> {
            String in = inputText.getText().toString();
            if (in.isEmpty()) { Toast.makeText(this,"请输入文字",Toast.LENGTH_SHORT).show(); return; }
            try {
                outputText.setText(encryptText(in, passwordEditText.getText().toString()));
                copyBtn.setVisibility(View.VISIBLE);
                generateQrBtn.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();
            }
        });

        decryptBtn.setOnClickListener(v -> {
            String in = inputText.getText().toString();
            if (in.isEmpty()) { Toast.makeText(this,"请输入文字",Toast.LENGTH_SHORT).show(); return; }
            try {
                outputText.setText(decryptText(in, passwordEditText.getText().toString()));
                copyBtn.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();
            }
        });

        copyBtn.setOnClickListener(v -> {
            String txt = outputText.getText().toString();
            if (!txt.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("哈基米", txt));
                lastCopiedText = txt;
                Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();
                if (preferences.getBoolean(KEY_AUTO_CLEAR,false)) startClearClipboardTimer();
            }
        });

        clearBtn.setOnClickListener(v -> {
            inputText.setText(""); passwordEditText.setText(""); outputText.setText("");
            copyBtn.setVisibility(View.GONE); generateQrBtn.setVisibility(View.GONE);
            cancelClearClipboardTimer();
        });

        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip()) inputText.setText(cm.getPrimaryClip().getItemAt(0).getText());
        });

        generateQrBtn.setOnClickListener(v -> {
            String txt = outputText.getText().toString();
            if (!txt.isEmpty()) showQrCodeDialog(txt);
        });

        scanQrBtn.setOnClickListener(v -> {
            ScanOptions opt = new ScanOptions();
            opt.setPrompt("请将二维码放入框内");
            opt.setBeepEnabled(false);
            opt.setOrientationLocked(true);
            opt.setCaptureActivity(CapturePortraitActivity.class);
            barcodeLauncher.launch(opt);
        });

        scanFromGalleryBtn.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        shareBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, outputText.getText().toString());
            startActivity(Intent.createChooser(i,"分享"));
        });
    }

    private void updateCharCount() {
        charCount.setText(inputText.getText().length() + " / 无限");
    }

    private void startClearClipboardTimer() {
        cancelClearClipboardTimer();
        int delay = preferences.getInt(KEY_CLEAR_DELAY, DEFAULT_CLEAR_DELAY);
        clearClipboardTimer = new CountDownTimer(delay * 1000L, 1000) {
            public void onTick(long millisUntilFinished) {}
            public void onFinish() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip()) {
                    String current = cm.getPrimaryClip().getItemAt(0).getText().toString();
                    if (current.equals(lastCopiedText)) {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""));
                        Toast.makeText(MainActivity.this, "剪贴板已自动清除", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }.start();
    }

    private void cancelClearClipboardTimer() {
        if (clearClipboardTimer != null) {
            clearClipboardTimer.cancel();
            clearClipboardTimer = null;
        }
    }

    private void showQrCodeDialog(String text) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_qr_code, null);
        ImageView iv = view.findViewById(R.id.qrCodeImage);
        Button saveBtn = view.findViewById(R.id.btnSaveQr);
        try {
            Bitmap bmp = generateQrCode(text, 600, 600);
            iv.setImageBitmap(bmp);
            saveBtn.setOnClickListener(v -> saveBitmapToGallery(bmp));
        } catch (WriterException e) {
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
            return;
        }
        builder.setView(view);
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    private Bitmap generateQrCode(String text, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix bitMatrix = new MultiFormatWriter()
                .encode(text, BarcodeFormat.QR_CODE, width, height, hints);
        return new BarcodeEncoder().createBitmap(bitMatrix);
    }

    private String decodeQRCodeFromBitmap(Bitmap bmp) {
        if (bmp == null) return null;
        bmp = cropWhiteBorder(bmp);
        if (bmp.getWidth() < 400 || bmp.getHeight() < 400)
            bmp = Bitmap.createScaledBitmap(bmp, bmp.getWidth()*2, bmp.getHeight()*2, true);
        try {
            int[] p = new int[bmp.getWidth()*bmp.getHeight()];
            bmp.getPixels(p, 0, bmp.getWidth(), 0,0,bmp.getWidth(),bmp.getHeight());
            BinaryBitmap bin = new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(bmp.getWidth(), bmp.getHeight(), p)));
            Map<DecodeHintType,Object> h = new HashMap<>();
            h.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
            h.put(DecodeHintType.CHARACTER_SET,"UTF-8");
            return new MultiFormatReader().decode(bin,h).getText();
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    private static Bitmap cropWhiteBorder(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w*h]; src.getPixels(px,0,w,0,0,w,h);
        int l=0,t=0,r=w-1,b=h-1;
        for (int x=0;x<w;x++){int c=0;for(int y=0;y<h;y++)if((px[y*w+x]&0x00FFFFFF)==0x00FFFFFF)c++;else break;if(c<h-10){l=x;break;}}
        for (int x=w-1;x>=0;x--){int c=0;for(int y=0;y<h;y++)if((px[y*w+x]&0x00FFFFFF)==0x00FFFFFF)c++;else break;if(c<h-10){r=x;break;}}
        for (int y=0;y<h;y++){int c=0;for(int x=0;x<w;x++)if((px[y*w+x]&0x00FFFFFF)==0x00FFFFFF)c++;else break;if(c<w-10){t=y;break;}}
        for (int y=h-1;y>=0;y--){int c=0;for(int x=0;x<w;x++)if((px[y*w+x]&0x00FFFFFF)==0x00FFFFFF)c++;else break;if(c<w-10){b=y;break;}}
        int nw=r-l+1, nh=b-t+1;
        if(nw<=0||nh<=0||(nw==w&&nh==h)) return src;
        return Bitmap.createBitmap(src,l,t,nw,nh);
    }

    private String encryptText(String text,String password) throws Exception {
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        data=xor(data,hash(password));
        StringBuilder bin=new StringBuilder();
        for(byte b:data) bin.append(String.format("%8s",Integer.toBinaryString(b&0xFF)).replace(' ','0'));
        int pad=(BITS_PER_CHAR-bin.length()%BITS_PER_CHAR)%BITS_PER_CHAR;
        bin.append("0".repeat(pad));
        StringBuilder out=new StringBuilder();
        for(int i=0;i<bin.length();i+=BITS_PER_CHAR){
            int idx=Integer.parseInt(bin.substring(i,i+BITS_PER_CHAR),2);
            out.append(CODE_CHARS[idx]);
        }
        out.append(CODE_CHARS[pad]);
        if(hash(password).length>0) out.append(CODE_CHARS[CODE_CHARS.length-1]);
        return out.toString();
    }

    private String decryptText(String text,String password) throws Exception {
        boolean needPwd=text.endsWith(String.valueOf(CODE_CHARS[CODE_CHARS.length-1]));
        String body=needPwd?text.substring(0,text.length()-1):text;
        if(needPwd && (password==null || password.isEmpty()))
            throw new IllegalArgumentException("请输入方言");
        char padChar=body.charAt(body.length()-1);
        int pad=CHAR_TO_INDEX.get(padChar);
        body=body.substring(0,body.length()-1);
        StringBuilder bin=new StringBuilder();
        for(char c:body.toCharArray()){
            int idx=CHAR_TO_INDEX.get(c);
            bin.append(String.format("%"+BITS_PER_CHAR+"s",Integer.toBinaryString(idx)).replace(' ','0'));
        }
        if(pad>0) bin.setLength(bin.length()-pad);
        if(bin.length()%8!=0) throw new IllegalArgumentException("数据错误");
        byte[] data=new byte[bin.length()/8];
        for(int i=0;i<data.length;i++) data[i]=(byte)Integer.parseInt(bin.substring(i*8,(i+1)*8),2);
        data=xor(data,hash(password));
        String result=new String(data,StandardCharsets.UTF_8);
        if(needPwd && !isReadable(result))
            throw new IllegalArgumentException("方言错误，解密失败");
        return result;
    }

    private byte[] hash(String pwd) {
        try {
            return pwd == null || pwd.isEmpty()
                    ? new byte[0]
                    : MessageDigest.getInstance("SHA-256")
                                   .digest(pwd.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ignore) {
            return new byte[0];
        }
    }

    private byte[] xor(byte[] data, byte[] key) {
        if (key.length == 0) return data;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ key[i % key.length]);
        return out;
    }

    private boolean isReadable(String s) {
        for (char c : s.toCharArray()) if (c < 0x20 || c > 0x7E) return false;
        return true;
    }

    private boolean isHakimiEncryptedText(String t) {
        if (t == null || t.length() < 2) return false;
        for (char c : t.toCharArray()) if (!CHAR_TO_INDEX.containsKey(c)) return false;
        return true;
    }

    private void autoDecrypt(String enc) {
        try {
            boolean needPwd = enc.endsWith(String.valueOf(CODE_CHARS[CODE_CHARS.length - 1]));
            if (needPwd) {
                String pwd = passwordEditText.getText().toString();
                if (pwd.isEmpty()) {
                    Toast.makeText(this, "此内容需要方言，请输入方言", Toast.LENGTH_LONG).show();
                    passwordEditText.requestFocus();
                    return;
                }
                outputText.setText(decryptText(enc, pwd));
            } else {
                outputText.setText(decryptText(enc, ""));
            }
            copyBtn.setVisibility(View.VISIBLE);
            Toast.makeText(this, "自动解密成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        getMenuInflater().inflate(R.menu.main_menu, m);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == R.id.action_settings) {
            showSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(i);
    }

    private void showSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        SwitchCompat sw = v.findViewById(R.id.autoClearSwitch);
        EditText et = v.findViewById(R.id.clearDelayEditText);
        sw.setChecked(preferences.getBoolean(KEY_AUTO_CLEAR, false));
        et.setText(String.valueOf(preferences.getInt(KEY_CLEAR_DELAY, DEFAULT_CLEAR_DELAY)));
        b.setView(v);
        b.setPositiveButton("保存", (d, w) -> {
            int delay = DEFAULT_CLEAR_DELAY;
            try {
                delay = Integer.parseInt(et.getText().toString());
                if (delay < 5) delay = 5;
                if (delay > 300) delay = 300;
            } catch (Exception ignored) {}
            preferences.edit()
                    .putBoolean(KEY_AUTO_CLEAR, sw.isChecked())
                    .putInt(KEY_CLEAR_DELAY, delay)
                    .apply();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            if (!sw.isChecked()) cancelClearClipboardTimer();
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void saveBitmapToGallery(Bitmap bmp) {
        String name = "Hajimi" + System.currentTimeMillis() + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Hajimi");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            Toast.makeText(this, "创建文件失败", Toast.LENGTH_SHORT).show();
            return;
        }
        try (OutputStream os =getContentResolver().openOutputStream(uri)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, os);
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }
}