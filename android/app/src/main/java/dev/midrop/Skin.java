package dev.midrop;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Skin {
    final Context c;
    final boolean dark;
    final int bg,card,text,muted,line,mint,ink;
    Skin(Context c) {
        this.c=c; dark=(c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        bg=Color.parseColor(dark?"#101514":"#F4F6F5"); card=Color.parseColor(dark?"#1B2321":"#FFFFFF");
        text=Color.parseColor(dark?"#F1F5F3":"#192420"); muted=Color.parseColor(dark?"#9BAAA3":"#75817B");
        line=Color.parseColor(dark?"#303C37":"#E2E8E4"); mint=Color.parseColor("#8FE1BF"); ink=Color.parseColor("#172A23");
    }
    int dp(float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    TextView text(String s,int size,int color) { TextView t=new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false); return t; }
    LinearLayout column() { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    LinearLayout card() { LinearLayout l=column(); l.setPadding(dp(20),dp(20),dp(20),dp(20)); l.setBackground(shape(card,24)); return l; }
    void gap(LinearLayout l,int n) { l.addView(new View(c),new LinearLayout.LayoutParams(1,dp(n))); }
    Button button(String s,boolean primary) {
        Button b=new Button(c); b.setText(s); b.setTextSize(15); b.setAllCaps(false); b.setTextColor(primary?ink:text);
        b.setBackground(shape(primary?mint:(dark?Color.parseColor("#2B3731"):Color.parseColor("#EDF2EF")),16));
        b.setMinHeight(dp(50)); b.setMinimumHeight(dp(50)); b.setStateListAnimator(null);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50)); p.topMargin=dp(12); b.setLayoutParams(p); return b;
    }
}
