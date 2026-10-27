package net.phoxis.tvmqtt;
import fi.iki.elonen.NanoWSD;
import java.io.*;
import java.nio.charset.CharacterCodingException;
import org.junit.*;
import static org.junit.Assert.*;

public class WebSocketBoundsTest {
    @Test public void overlargeFramesFailBeforePayloadAllocation() throws Exception {
        byte[] frame={(byte)0x81,(byte)0xfe,0x40,0x01,1,2,3,4};
        try { NanoWSD.WebSocketFrame.read(new ByteArrayInputStream(frame));fail(); }
        catch(NanoWSD.WebSocketException error) { assertEquals(NanoWSD.WebSocketFrame.CloseCode.MessageTooBig,error.getCode()); }
    }
    @Test public void clientFramesMustBeMasked() throws Exception {
        try { NanoWSD.WebSocketFrame.read(new ByteArrayInputStream(new byte[]{(byte)0x81,0x02,'h','i'}));fail(); }
        catch(NanoWSD.WebSocketException error) { assertEquals(NanoWSD.WebSocketFrame.CloseCode.ProtocolError,error.getCode()); }
    }
    @Test public void malformedUtf8IsRejected() throws Exception {
        try { NanoWSD.WebSocketFrame.binary2Text(new byte[]{(byte)0xc3,0x28});fail(); }
        catch(CharacterCodingException expected) {}
    }
}
