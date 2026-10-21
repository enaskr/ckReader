package com.selvasai.selvy.deeptts;

/** Data object referenced by optional Selvy playlist JNI entry points. */
public final class PlayTTSListItem {
    public String text="";
    public String tag="";
    public long requestId=-1;
    public long pauseTime=0;
    public PlayTTSListItem(){}
    public PlayTTSListItem(String text,String tag,long requestId,long pauseTime){
        this.text=text;this.tag=tag;this.requestId=requestId;this.pauseTime=pauseTime;
    }
    public String getText(){return text;}
    public String getTag(){return tag;}
    public long getRequestId(){return requestId;}
    public long getPauseTime(){return pauseTime;}
}
