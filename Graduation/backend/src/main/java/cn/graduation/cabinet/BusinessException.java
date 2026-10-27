package cn.graduation.cabinet;

/** 可预期的业务错误：统一返回中文提示，避免前端收到Java异常栈。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
