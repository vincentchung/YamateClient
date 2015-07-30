package com.mojo.yamateclient;

import org.json.JSONException;
import org.json.JSONObject;

public class TrackInfo {

    public static String JSON_TRACK_URI = "track_uri";
    public static String JSON_DURATION_IN_MS = "duration_in_ms";
    public static String JSON_PLAYING = "playing";
    public static String JSON_POSITION_IN_MS = "position_in_ms";

    private String mTrackUri;
    private int mDurationInMs = -1;
    private boolean mPlaying = false;
    private int mPositionInMs = -1;

    public TrackInfo() {
    }

    public void setTrackUri(String trackUri) {
        mTrackUri = trackUri;
    }

    public void setDurationInMs(int durationInMs) {
        mDurationInMs = durationInMs;
    }

    public void setPlaying(boolean playing) {
        mPlaying = playing;
    }

    public void setPositionInMs(int positionInMs) {
        mPositionInMs = positionInMs;
    }

    public static TrackInfo deserializeFromObj(JSONObject object) throws JSONException {
        TrackInfo info = new TrackInfo();
        if (object.has(JSON_TRACK_URI)) {
            info.setTrackUri(object.getString(JSON_TRACK_URI));
        }
        if (object.has(JSON_DURATION_IN_MS)) {
            info.setDurationInMs(object.getInt(JSON_DURATION_IN_MS));
        }
        if (object.has(JSON_PLAYING)) {
            info.setPlaying(object.getBoolean(JSON_PLAYING));
        }
        if (object.has(JSON_POSITION_IN_MS)) {
            info.setPositionInMs(object.getInt(JSON_POSITION_IN_MS));
        }

        return info;
    }

    @Override
    public String toString() {
        return "TrackInfo [trackUri=" + mTrackUri + ", durationInMs=" + mDurationInMs +
            " playing=" + mPlaying + ", positionInMs=" + mPositionInMs + "]";
    }
}
