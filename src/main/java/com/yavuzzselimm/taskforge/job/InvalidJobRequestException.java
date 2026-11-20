package com.yavuzzselimm.taskforge.job;

public class InvalidJobRequestException extends RuntimeException {

    public InvalidJobRequestException(String message) {
        super(message);
    }
}