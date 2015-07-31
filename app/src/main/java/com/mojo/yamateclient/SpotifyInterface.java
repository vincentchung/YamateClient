package com.mojo.yamateclient;

import org.alljoyn.bus.BusException;
import org.alljoyn.bus.annotation.BusInterface;
import org.alljoyn.bus.annotation.BusMethod;

@BusInterface(name = "com.mojo.YamateServer.SpotifyInterface")
public interface SpotifyInterface {

    /*
     * The BusMethod annotation signifies that this function should be used as part of the AllJoyn
     * interface.  The runtime is smart enough to figure out what the input and output of the method
     * is based on the input/output arguments of the Ping method.
     *
     * All methods that use the BusMethod annotation can throw a BusException and should indicate
     * this fact.
     */
    @BusMethod
    String Ping(String inStr) throws BusException;

    @BusMethod
    String PlaySpotifyAudioStream(String accessToken, final String playlistUri) throws BusException;

    @BusMethod
    String PlaySpotifyCurrentTrack(final String trackUri, int positionInMs) throws BusException;

    @BusMethod
    String ChangeSpotifyStats(int stats) throws BusException;

    @BusMethod
    void TogglePlay() throws BusException;
}
