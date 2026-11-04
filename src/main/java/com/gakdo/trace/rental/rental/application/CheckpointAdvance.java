package com.gakdo.trace.rental.rental.application;

/**
 * 시점 이벤트를 적용한 결과. 짝이 맞지 않는 이벤트는 리스너가 DLQ로 보내 사람이 볼 수 있게 한다.
 */
public enum CheckpointAdvance {
    /** 상태가 앞으로 옮겨졌다. */
    CHANGED,
    /** 이미 그 상태이거나 더 뒤라 바뀐 것이 없다(중복·늦게 온 이벤트). */
    UNCHANGED,
    /** rental-server에 없는 시점이다. */
    UNKNOWN_CHECKPOINT,
    /** 시점은 있지만 이벤트의 rentalId가 그 시점의 렌탈과 다르다. */
    RENTAL_MISMATCH;

    /** 이벤트의 렌탈·시점 짝이 rental-server 기록과 어긋났는지. */
    public boolean isMismatch() {
        return this == UNKNOWN_CHECKPOINT || this == RENTAL_MISMATCH;
    }
}
