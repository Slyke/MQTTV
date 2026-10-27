package net.phoxis.tvmqtt;
import java.util.*;

public final class AndroidTvKeys {
    private static final Map<String,Integer> CODES=new HashMap<>();
    static {
        String[] names={"home","back","up","down","left","right","ok","input","play","pause","stop","rewind","fast_forward","next","previous","options","info","channel_up","channel_down","power_on","power_off","volume_up","volume_down","mute"};
        int[] codes={3,4,19,20,21,22,23,178,126,127,86,89,90,87,88,82,165,166,167,224,223,24,25,164};
        for(int i=0;i<names.length;i++) CODES.put(names[i],codes[i]);
    }
    public static int code(String name) throws CommandFailure {
        Integer code=CODES.get(name);
        if(code==null) throw new CommandFailure("invalid_value","Unknown Android TV key");
        return code;
    }
    public static boolean supported(String name) { return CODES.containsKey(name); }
}
