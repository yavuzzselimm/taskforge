package com.yavuzzselimm.taskforge.handler;

/**
 * Bir iş türünü çalıştıran kod. Yeni bir iş türü eklemek için bu arayüzü
 * uygulayan bir @Component yazmak yeterlidir; çekirdeğe dokunmak gerekmez.
 */
public interface JobHandler {

    /** Bu handler'ın çalıştırdığı iş türü, örn. "SEND_ORDER_EMAIL". */
    String jobType();

    /**
     * İşi çalıştırır. Normal dönerse iş başarılı sayılır;
     * exception fırlatırsa başarısız sayılır.
     */
    void handle(JobContext context) throws Exception;
}