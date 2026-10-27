package net.phoxis.tvmqtt;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.common.BitMatrix;

public final class QrActivity extends Activity {
    public static void show(Activity parent,String title,String value,String note) {
        if(value==null || value.isEmpty()) {
            Toast.makeText(parent,"There is no value to show",Toast.LENGTH_LONG).show();return;
        }
        parent.startActivity(new Intent(parent,QrActivity.class).putExtra("title",title).putExtra("value",value).putExtra("note",note));
    }
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            String title=getIntent().getStringExtra("title"),value=getIntent().getStringExtra("value");
            BitMatrix matrix=QrCodes.encode(value);
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(32),dp(16),dp(32),dp(16));root.setBackgroundColor(Color.rgb(20,37,43));
            TextView heading=text(title,25);root.addView(heading);
            LinearLayout body=new LinearLayout(this);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(new CodeView(matrix),new LinearLayout.LayoutParams(0,-1,1));
            LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(dp(24),0,0,0);
            right.addView(text(getIntent().getStringExtra("note"),16));
            ScrollView scroller=new ScrollView(this);TextView content=text(value,16);
            content.setTypeface(Typeface.MONOSPACE);content.setTextIsSelectable(true);
            content.setPadding(0,dp(16),0,dp(16));scroller.addView(content);
            right.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
            Button close=new Button(this);close.setText("Back");close.setAllCaps(false);close.setOnClickListener(view->finish());
            right.addView(close,new LinearLayout.LayoutParams(-1,dp(54)));
            body.addView(right,new LinearLayout.LayoutParams(0,-1,1));
            root.addView(body,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);close.requestFocus();
        } catch(Exception e) {
            Toast.makeText(this,"This value could not fit in one QR code",Toast.LENGTH_LONG).show();finish();
        }
    }
    private TextView text(String value,int size) {
        TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(Color.WHITE);return text;
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private final class CodeView extends View {
        private final BitMatrix matrix;private final Paint paint=new Paint();
        CodeView(BitMatrix matrix) { super(QrActivity.this);this.matrix=matrix;paint.setColor(Color.BLACK);paint.setAntiAlias(false); }
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int scale=Math.max(1,Math.min(getWidth(),getHeight())/matrix.getWidth());
            int side=matrix.getWidth()*scale,x=(getWidth()-side)/2,y=(getHeight()-side)/2;
            Paint white=new Paint();white.setColor(Color.WHITE);
            canvas.drawRect(x,y,x+side,y+side,white);
            for(int row=0;row<matrix.getHeight();row++) for(int col=0;col<matrix.getWidth();col++)
                if(matrix.get(col,row)) canvas.drawRect(x+col*scale,y+row*scale,x+(col+1)*scale,y+(row+1)*scale,paint);
        }
    }
}
