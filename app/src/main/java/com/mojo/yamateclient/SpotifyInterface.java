package com.mojo.yamateclient;

import org.alljoyn.bus.BusException;
import org.alljoyn.bus.annotation.BusInterface;
import org.alljoyn.bus.annotation.BusMethod;

@BusInterface(name = "com.ubnt.sleepdroid.server.SpotifyInterface")
public interface SpotifyInterface {

    @BusMethod
    void PlayCurrentTrack(final String trackUri, int positionInMs) throws BusException;

    @BusMethod
    void TogglePlay() throws BusException;

    @BusMethod
    String GetTrackInfo() throws BusException;
}
