package net.phoxis.tvmqtt;

public final class TextDeletion {
    public final String text;
    public final int cursor;
    private TextDeletion(String text,int cursor) { this.text=text;this.cursor=cursor; }
    public static TextDeletion backspace(String text,int start,int end) { return backspace(text,start,end,1); }
    public static TextDeletion backspace(String text,int start,int end,int count) {
        if(count<1 || count>240) throw new IllegalArgumentException("Invalid backspace count");
        if(start<0 || end<0 || start>text.length() || end>text.length()) start=end=text.length();
        int left=Math.min(start,end),right=Math.max(start,end);
        int remaining=count-(left==right?0:1);
        int available=text.codePointCount(0,left);
        left=text.offsetByCodePoints(left,-Math.min(remaining,available));
        return new TextDeletion(text.substring(0,left)+text.substring(right),left);
    }
}
