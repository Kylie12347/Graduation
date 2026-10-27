package cn.graduation.cabinet;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e) { return error(409,e.getMessage()); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) { return error(e.getStatusCode().value(),e.getReason()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        var field=e.getBindingResult().getFieldErrors().get(0);
        return error(400,"参数错误："+field.getField()+" "+field.getDefaultMessage());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() { return error(400,"请求JSON格式错误或缺少必填字段"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate() { return error(409,"编号重复或数据约束冲突，请刷新后重试"); }
    private ResponseEntity<?> error(int code,String message) {
        return ResponseEntity.status(code).body(Map.of("message",message==null?"请求失败":message));
    }
}
