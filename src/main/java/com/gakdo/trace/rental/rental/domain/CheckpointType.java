package com.gakdo.trace.rental.rental.domain;

/** codes.yaml rental.checkpoint_type. 선언 순서가 곧 시점 순서다(출고 → 수령 → 반납). */
public enum CheckpointType {
    OUT, RCV, RET;

    /** 이 시점 바로 앞 시점. OUT이면 null. */
    public CheckpointType previous() {
        return ordinal() == 0 ? null : values()[ordinal() - 1];
    }
}
