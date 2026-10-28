package cn.graduation.cabinet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static cn.graduation.cabinet.Models.*;

/**
 * 教学演示登录：消费者为服务端生成的匿名身份，管理员使用环境变量密码。
 * 不接微信真实登录和支付。随机会话令牌有效12小时，仅保存在内存；服务重启需重新登录。
 */
@Service
public class AuthService {
    private record Entry(Principal principal, long expiresAt) {}
    private final Map<String,Entry> tokens = new ConcurrentHashMap<>();
    private final String password;
    public AuthService(@Value("${app.admin-password}") String password) { this.password=password; }
    public Map<String,Object> login(Login request) {
        if(!Set.of("ADMIN","CONSUMER").contains(request.role())) throw new BusinessException("角色只能是ADMIN或CONSUMER");
        boolean admin="ADMIN".equals(request.role());
        if(admin && (request.password()==null || !MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
            request.password().getBytes(StandardCharsets.UTF_8))))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"管理员密码错误");
        // 不接受客户端指定他人的userId，避免通过换一个编号读取其他人的订单。
        String user=admin ? "admin" : "guest-"+DataStore.id();
        String token=DataStore.id()+DataStore.id();
        long expiry=DataStore.now()+12*60*60*1000L;
        tokens.entrySet().removeIf(e -> e.getValue().expiresAt()<DataStore.now());
        tokens.put(token,new Entry(new Principal(user,admin),expiry));
        return Map.of("token",token,"userId",user,"role",request.role(),"expiresAt",expiry);
    }
    public Principal authenticate(String authorization) {
        String token=authorization!=null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        Entry e=tokens.get(token);
        if(e==null || e.expiresAt()<DataStore.now())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已过期，请重新登录");
        return e.principal();
    }
}
