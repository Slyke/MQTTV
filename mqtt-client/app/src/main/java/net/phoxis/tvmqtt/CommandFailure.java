package net.phoxis.tvmqtt;
public final class CommandFailure extends Exception {
    public final String code;
    public CommandFailure(String code, String message) { super(message); this.code = code; }
}
