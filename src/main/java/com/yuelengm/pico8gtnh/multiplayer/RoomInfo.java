package com.yuelengm.pico8gtnh.multiplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Public room metadata, independent of Minecraft's client classes. */
public final class RoomInfo {

    public UUID id;
    public UUID host;
    public String hostName;
    public String cartName;
    public String runtimeHash;
    public int fps;
    public long epoch;
    public boolean published;
    public final List<Member> members = new ArrayList<>();

    public static final class Member {

        public final UUID id;
        public final String name;
        public boolean controller;
        public long revision;
        public long controlFrame;

        public Member(UUID id, String name, boolean controller) {
            this.id = id;
            this.name = name;
            this.controller = controller;
        }
    }

    public Member member(UUID id) {
        for (Member member : members) if (member.id.equals(id)) return member;
        return null;
    }
}
