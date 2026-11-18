package ir.maniimani.geekplay;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Minimal client for the head unit's vendor "car" binder service (android.car.server).
 * Speaks the same wire protocol as the vendor's own BT app, so we can read the
 * Bluetooth music state and send play/pause/next/prev without the vendor UI.
 *
 * Protocol (from android.car.server.ICarService):
 *   5 = requestSync(String what, int, int, String, Bundle inout, int) -> int
 *   6 = requestOneway(String what, int, int, String, Bundle, int)
 */
final class CarBt {

    private static final String DESCRIPTOR = "android.car.server.ICarService";
    private static final int TX_REQUEST_SYNC = 5;
    private static final int TX_REQUEST_ONEWAY = 6;

    // Data types (CarDataManager.DataType ordinals)
    private static final int TYPE_INT = 2;
    private static final int TYPE_STRING = 6;
    private static final int TYPE_STRING_ARRAY = 15;

    // Keys
    static final String KEY_AVRCP_INFO = "state.bt.avrcp_info";   // String[]: title, artist, totalTime, index, count
    static final String KEY_A2DP_STATE = "state.bt.a2dp_state";   // 0 Idle, 1 Playing, 2 Pause, 3 Stop
    static final String KEY_HFP_STATE = "state.bt.hfp_state";     // 3..6 = connected
    static final String KEY_REMOTE_NAME = "state.bt.remote_name";
    static final String KEY_CUR_SOURCE = "state.app.cur_source";

    static final int APP_ID_BT_MUSIC = 65537;

    static final int A2DP_PLAYING = 1;

    private IBinder binder;

    synchronized boolean ensureConnected() {
        if (binder != null && binder.isBinderAlive()) return true;
        binder = null;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "car");
        } catch (Throwable ignored) {
            binder = null;
        }
        return binder != null;
    }

    // ------------------------------------------------------------------ raw calls

    private int requestSync(String what, Bundle inout) {
        IBinder b = binder;
        if (b == null) return 0;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(what);
            data.writeInt(0);
            data.writeInt(0);
            data.writeString(null);
            if (inout != null) {
                data.writeInt(1);
                inout.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            data.writeInt(0);
            b.transact(TX_REQUEST_SYNC, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            if (reply.readInt() != 0 && inout != null) {
                inout.setClassLoader(CarBt.class.getClassLoader());
                inout.readFromParcel(reply);
            }
            return result;
        } catch (Throwable t) {
            return 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void requestOneway(String what, Bundle extras) {
        IBinder b = binder;
        if (b == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(what);
            data.writeInt(0);
            data.writeInt(0);
            data.writeString(null);
            if (extras != null) {
                data.writeInt(1);
                extras.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            data.writeInt(0);
            b.transact(TX_REQUEST_ONEWAY, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
        } finally {
            data.recycle();
        }
    }

    private Bundle getValue(String key, int type) {
        Bundle b = new Bundle();
        b.putString("key", key);
        b.putInt("type", type);
        requestSync("request.dm.get_value", b);
        return b;
    }

    // ------------------------------------------------------------------ typed getters

    String[] getStringArray(String key) {
        try {
            return getValue(key, TYPE_STRING_ARRAY).getStringArray("value");
        } catch (Throwable t) {
            return null;
        }
    }

    int getInt(String key, int def) {
        try {
            return getValue(key, TYPE_INT).getInt("value", def);
        } catch (Throwable t) {
            return def;
        }
    }

    String getString(String key) {
        try {
            return getValue(key, TYPE_STRING).getString("value");
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ commands

    void playPause() {
        requestOneway("request.bt.a2dp_play_pause", null);
    }

    void next() {
        requestOneway("request.bt.a2dp_next", null);
    }

    void prev() {
        requestOneway("request.bt.a2dp_prev", null);
    }

    /** Switch the head unit's audio source to Bluetooth music (opens the vendor BT app briefly). */
    void enterBtMusicSource() {
        Bundle b = new Bundle();
        b.putInt("app_id", APP_ID_BT_MUSIC);
        b.putString("reason", "GeekPlay");
        requestSync("request.app.enter_app", b);
    }

    // ------------------------------------------------------------------ snapshot

    static final class State {
        boolean serviceOk;
        boolean connected;
        boolean playing;
        int a2dp;
        String title = "";
        String artist = "";
        long totalMs = -1;
        int index = -1;
        int count = -1;
        String deviceName = "";
        int source;
    }

    State read() {
        State s = new State();
        s.serviceOk = ensureConnected();
        if (!s.serviceOk) return s;

        int hfp = getInt(KEY_HFP_STATE, 0);
        s.a2dp = getInt(KEY_A2DP_STATE, 0);
        s.playing = s.a2dp == A2DP_PLAYING;
        s.connected = (hfp >= 3 && hfp <= 6) || s.a2dp == 1 || s.a2dp == 2;
        s.source = getInt(KEY_CUR_SOURCE, 0);
        String name = getString(KEY_REMOTE_NAME);
        s.deviceName = name == null ? "" : name.trim();

        String[] info = getStringArray(KEY_AVRCP_INFO);
        if (info != null) {
            if (info.length > 0 && info[0] != null) s.title = info[0].trim();
            if (info.length > 1 && info[1] != null) s.artist = info[1].trim();
            if (info.length > 2) s.totalMs = parseTime(info[2]);
            if (info.length > 3) s.index = parseIntSafe(info[3]);
            if (info.length > 4) s.count = parseIntSafe(info[4]);
        }
        return s;
    }

    /** Accepts "m:ss", "h:mm:ss", plain milliseconds or plain seconds. */
    static long parseTime(String v) {
        if (v == null) return -1;
        v = v.trim();
        if (v.isEmpty()) return -1;
        try {
            if (v.contains(":")) {
                long total = 0;
                for (String part : v.split(":")) total = total * 60 + Long.parseLong(part.trim());
                return total > 0 ? total * 1000 : -1;
            }
            long n = Long.parseLong(v);
            if (n <= 0) return -1;
            return n > 36000 ? n : n * 1000; // >10h in seconds is unrealistic → it's ms
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseIntSafe(String v) {
        try {
            return v == null ? -1 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
