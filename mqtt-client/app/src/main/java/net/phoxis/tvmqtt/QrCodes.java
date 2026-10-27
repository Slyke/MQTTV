package net.phoxis.tvmqtt;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

public final class QrCodes {
    private QrCodes() {}
    public static BitMatrix encode(String value) throws WriterException {
        if(value==null || value.isEmpty()) throw new IllegalArgumentException("There is no value to show");
        Map<EncodeHintType,Object> options=new EnumMap<>(EncodeHintType.class);
        options.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        options.put(EncodeHintType.MARGIN,4);
        options.put(EncodeHintType.ERROR_CORRECTION,value.length()>900?ErrorCorrectionLevel.L:ErrorCorrectionLevel.M);
        return new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,0,0,options);
    }
}
