package com.yavuzzselimm.taskforge.handler;

/**
 * Tekrar denemenin işe yaramayacağı hatalar için (ör. geçersiz payload).
 * Handler bunu fırlatırsa iş deneme hakkı kalsa bile doğrudan DEAD olur.
 * Diğer tüm exception'lar geçici sayılır ve tekrar denenir.
 */
public class PermanentJobException extends Exception {

    public PermanentJobException(String message) {
        super(message);
    }

    public PermanentJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
