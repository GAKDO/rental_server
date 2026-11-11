package com.gakdo.trace.rental.rental.domain;

/** codes.yaml rental.checkpoint_status. 선언 순서대로 앞으로만 간다. */
public enum CheckpointStatus {
    OPEN, CAPTURED, GRADED, SEALED;

    public boolean isBefore(CheckpointStatus other) {
        return ordinal() < other.ordinal();
    }
}
