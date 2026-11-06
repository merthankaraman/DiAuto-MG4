package com.saicmotor.common;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The car apps' command, as their own com.saicmotor.common.CommandParcelable writes it: the class
 * name travels in the Intent extra, so it has to be this one. Fields: event, package, target
 * type, target state, parameters.
 */
public class CommandParcelable implements Parcelable {
    public static final String COMMAND_KEY = "command";

    public int event = -1;
    public String pkg = "";
    public int targetType = -1;
    public int targetState = -1;
    public Bundle params = new Bundle();

    public CommandParcelable() {
    }

    protected CommandParcelable(Parcel in) {
        event = in.readInt();
        pkg = in.readString();
        targetType = in.readInt();
        targetState = in.readInt();
        params = in.readBundle(Bundle.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(event);
        out.writeString(pkg);
        out.writeInt(targetType);
        out.writeInt(targetState);
        out.writeBundle(params);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CommandParcelable> CREATOR = new Creator<CommandParcelable>() {
        @Override
        public CommandParcelable createFromParcel(Parcel in) {
            return new CommandParcelable(in);
        }

        @Override
        public CommandParcelable[] newArray(int size) {
            return new CommandParcelable[size];
        }
    };
}
