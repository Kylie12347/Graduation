package cn.graduation.cabinet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import static cn.graduation.cabinet.Models.*;
import static cn.graduation.cabinet.DataStore.*;

/**
 * 购物业务的事务边界。库存分为“实际在柜数量”和“支付后销量”，避免取出时和支付时重复扣库存。
 * 柜体占用直到付款或归还取消，防止未结算会话与下一位消费者混用商品。
 */
@Service
public class ShoppingService {
    private final DataStore s;
    private final JsonCodec json;
    public ShoppingService(DataStore s,JsonCodec json) {this.s=s;this.json=json;}
    public Map<String,Object> checkOwner(String sessionId, Principal user) {
        Map<String,Object> session=s.one("SELECT * FROM shopping_sessions WHERE id=?",sessionId);
        if(!user.admin() && !user.userId().equals(session.get("user_id")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看该购物会话");
        return session;
    }
    /** 柜体行锁内调用。旧柜第一次改变锁状态时补建记录。 */
    private void setCabinetLock(String cabinetId,boolean locked) {
        if(s.jdbc().update("UPDATE cabinet_locks SET locked=? WHERE cabinet_id=?",locked,cabinetId)==0)
            s.jdbc().update("INSERT INTO cabinet_locks(cabinet_id,locked) VALUES(?,?)",cabinetId,locked);
    }
    @Transactional
    public Map<String,Object> open(String cabinetId, Principal user) {
        Map<String,Object> cabinet=s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",cabinetId);
        if(cabinet.get("active_session")!=null) throw new BusinessException("柜体正在购物或等待结算，请稍后再试");
        if(s.jdbc().queryForObject("SELECT COUNT(*) FROM shopping_sessions WHERE user_id=? AND state NOT IN ('PAID','CANCELLED')",Integer.class,user.userId())>0)
            throw new BusinessException("你有未完成的购物会话，请先完成结算");
        setCabinetLock(cabinetId,false);
        String session=id();
        s.jdbc().update("INSERT INTO shopping_sessions(id,user_id,cabinet_id,state,created_at) VALUES(?,?,?,'OPEN',?)",session,user.userId(),cabinetId,now());
        s.jdbc().update("UPDATE cabinets SET door_open=TRUE,active_session=? WHERE id=?",session,cabinetId);
        return detail(session,user);
    }
    /** 购物清单只根据融合事件计算，不读取simulation_actions真值表。 */
    public List<OrderItem> basket(String session) {
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(Map<String,Object> event:s.jdbc().queryForList("SELECT * FROM perception_events WHERE session_id=? ORDER BY created_at,id",session)) {
            if("PENDING".equals(event.get("status"))) continue;
            FusionResult f=json.read((String)event.get("result_json"),FusionResult.class);
            boolean manual="RESOLVED".equals(event.get("status"));
            String product=manual?(String)event.get("resolved_product"):f.productId();
            int qty=manual?((Number)event.get("resolved_qty")).intValue():f.quantity();
            String direction=manual?(String)event.get("resolved_direction"):f.direction();
            counts.merge(product,"TAKE".equals(direction)?qty:-qty,Integer::sum);
        }
        List<OrderItem> items=new ArrayList<>();
        for(var e:counts.entrySet()) {
            if(e.getValue()<0) throw new BusinessException("清单出现负数量，请先修正异常取放方向/商品");
            if(e.getValue()==0) continue;
            Product p=s.product(e.getKey());
            items.add(new OrderItem(p.id(),p.name(),e.getValue(),p.priceCents(),Math.multiplyExact(p.priceCents(),e.getValue())));
        }
        return items;
    }
    public Map<String,Object> detail(String sessionId,Principal user) {
        Map<String,Object> session=new LinkedHashMap<>(checkOwner(sessionId,user));
        List<Map<String,Object>> events=new ArrayList<>();
        for(var row:s.jdbc().queryForList("SELECT * FROM perception_events WHERE session_id=? ORDER BY created_at,id",sessionId)) {
            Map<String,Object> e=new LinkedHashMap<>(row);
            e.put("signal",json.read((String)e.remove("signal_json"),Signal.class));
            e.put("result",json.read((String)e.remove("result_json"),FusionResult.class));
            events.add(e);
        }
        var cabinet=s.one("SELECT c.door_open,COALESCE(l.locked,TRUE) AS locked FROM cabinets c LEFT JOIN cabinet_locks l ON l.cabinet_id=c.id WHERE c.id=?",session.get("cabinet_id"));
        session.put("doorOpen",cabinet.get("door_open"));
        session.put("cabinetLocked",cabinet.get("locked"));
        session.put("events",events);
        session.put("pendingCount",events.stream().filter(e->"PENDING".equals(e.get("status"))).count());
        try {session.put("items",basket(sessionId));session.put("basketError","");}
        catch(BusinessException e){session.put("items",List.of());session.put("basketError",e.getMessage());}
        if(session.get("order_id")!=null) session.put("order",order((String)session.get("order_id"),user));
        return session;
    }
    @Transactional
    public Map<String,Object> close(String sessionId,Principal user) {
        checkOwner(sessionId,user);
        var session=s.lockSession(sessionId);
        if(!"OPEN".equals(session.get("state"))) return detail(sessionId,user); // 重复关柜不重复生成订单。
        // 管理端“关柜”是纯软件门磁事件；关闭后保持占用，等待确认与支付。
        s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",session.get("cabinet_id"));
        setCabinetLock((String)session.get("cabinet_id"),false);
        String orderId=id();
        s.jdbc().update("INSERT INTO orders(id,session_id,user_id,cabinet_id,state,items_json,created_at) VALUES(?,?,?,?,'REVIEW','[]',?)",orderId,sessionId,session.get("user_id"),session.get("cabinet_id"),now());
        s.jdbc().update("UPDATE shopping_sessions SET state='REVIEW',closed_at=?,order_id=? WHERE id=?",now(),orderId,sessionId);
        s.jdbc().update("UPDATE cabinets SET door_open=FALSE WHERE id=?",session.get("cabinet_id"));
        return detail(sessionId,user);
    }
    @Transactional
    public Map<String,Object> resolve(String sessionId,String eventId,ResolveRequest request,Principal user) {
        checkOwner(sessionId,user);
        var session=s.lockSession(sessionId);
        if(!"REVIEW".equals(session.get("state"))) throw new BusinessException("请关柜后、确认清单前处理异常");
        s.product(request.productId());
        var event=s.one("SELECT * FROM perception_events WHERE id=? AND session_id=?",eventId,sessionId);
        // 允许在最终确认前修正人工确认；原始signal/result始终保留用于论文分析。
        if("CONFIRMED".equals(event.get("status"))) throw new BusinessException("自动确认事件不能在此修改");
        s.jdbc().update("UPDATE perception_events SET status='RESOLVED',resolved_product=?,resolved_qty=?,resolved_direction=?,resolution_note=? WHERE id=?",request.productId(),request.quantity(),request.direction(),request.note(),eventId);
        return detail(sessionId,user);
    }
    @Transactional
    public Map<String,Object> confirm(String orderId,Principal user) {
        var initial=order(orderId,user);
        var session=s.lockSession((String)initial.get("session_id"));
        var order=s.one("SELECT * FROM orders WHERE id=? FOR UPDATE",orderId);
        if(!"REVIEW".equals(order.get("state"))) return order(orderId,user);
        if(s.jdbc().queryForObject("SELECT COUNT(*) FROM perception_events WHERE session_id=? AND status='PENDING'",Integer.class,session.get("id"))>0)
            throw new BusinessException("还有待确认事件，请先处理");
        List<OrderItem> items=basket((String)session.get("id"));
        int total=items.stream().mapToInt(OrderItem::subtotalCents).reduce(0,Math::addExact);
        s.jdbc().update("UPDATE orders SET state='UNPAID',total_cents=?,items_json=? WHERE id=?",total,json.write(items),orderId);
        s.jdbc().update("UPDATE shopping_sessions SET state='UNPAID' WHERE id=?",session.get("id"));
        return order(orderId,user);
    }
    public Map<String,Object> order(String orderId,Principal user) {
        Map<String,Object> o=new LinkedHashMap<>(s.one("SELECT * FROM orders WHERE id=?",orderId));
        if(!user.admin() && !user.userId().equals(o.get("user_id"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看该订单");
        o.put("items",json.read((String)o.remove("items_json"),new TypeReference<List<OrderItem>>(){}));
        o.put("payments",s.jdbc().queryForList("SELECT * FROM payments WHERE order_id=? ORDER BY created_at",orderId));
        return o;
    }
    @Transactional
    public Map<String,Object> pay(String orderId,PaymentRequest request,Principal user) {
        var initial=order(orderId,user);
        var session=s.lockSession((String)initial.get("session_id"));
        var order=s.one("SELECT * FROM orders WHERE id=? FOR UPDATE",orderId);
        // 令牌幂等键由前端生成；重复成功请求不会重复增加销量或流水。
        var old=s.jdbc().queryForList("SELECT * FROM payments WHERE request_key=?",request.requestKey());
        if(!old.isEmpty()) {
            if(!orderId.equals(old.get(0).get("order_id"))) throw new BusinessException("支付请求编号已被其他订单使用");
            return order(orderId,user);
        }
        if("PAID".equals(order.get("state"))) return order(orderId,user);
        if(!"UNPAID".equals(order.get("state"))) throw new BusinessException("只有待支付订单可以支付");
        var cabinet=s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",order.get("cabinet_id"));
        if(Boolean.TRUE.equals(cabinet.get("door_open"))) throw new BusinessException("柜门尚未关闭，不能支付");
        if(!session.get("id").equals(cabinet.get("active_session"))) throw new BusinessException("柜体会话不一致，请联系管理员核对");
        s.jdbc().update("INSERT INTO payments VALUES(?,?,?,?,?,?)",id(),orderId,request.requestKey(),request.success()?"SUCCESS":"FAILED",order.get("total_cents"),now());
        if(!request.success()) return order(orderId,user); // 失败可重试，不增加销量。
        List<OrderItem> items=json.read((String)order.get("items_json"),new TypeReference<>(){});
        for(OrderItem item:items) {
            int updated=s.jdbc().update("UPDATE stocks SET sold=sold+? WHERE cabinet_id=? AND product_id=?",item.quantity(),order.get("cabinet_id"),item.productId());
            if(updated!=1) throw new BusinessException("确认商品尚未配置到本柜，请联系管理员核对");
            s.log((String)order.get("cabinet_id"),item.productId(),"SALE",item.quantity(),orderId);
        }
        s.jdbc().update("UPDATE orders SET state='PAID',paid_at=? WHERE id=?",now(),orderId);
        s.jdbc().update("UPDATE shopping_sessions SET state='PAID' WHERE id=?",session.get("id"));
        // 支付、销量、关门锁定和释放柜体在同一事务提交；失败不释放柜体。
        setCabinetLock((String)order.get("cabinet_id"),true);
        s.jdbc().update("UPDATE cabinets SET door_open=FALSE,active_session=NULL WHERE id=?",order.get("cabinet_id"));
        return order(orderId,user);
    }
    /** 取消不凭空恢复柜内商品；由后台执行可追溯的“全部归还并取消”。 */
    @Transactional
    public Map<String,Object> cancel(String orderId,Principal user) {
        var initial=order(orderId,user);
        var session=s.lockSession((String)initial.get("session_id"));
        if("CANCELLED".equals(session.get("state"))) return order(orderId,user);
        if("PAID".equals(session.get("state"))) throw new BusinessException("已支付订单不能取消");
        if(!SimulatorService.held(s,(String)session.get("id")).values().stream().allMatch(n->n==0))
            throw new BusinessException("有商品在柜外，请管理员在仿真面板执行“全部归还并取消”");
        finishCancel(session,orderId);
        return order(orderId,user);
    }
    void finishCancel(Map<String,Object> session,String orderId) {
        s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",session.get("cabinet_id"));
        setCabinetLock((String)session.get("cabinet_id"),true);
        s.jdbc().update("UPDATE orders SET state='CANCELLED' WHERE id=?",orderId);
        s.jdbc().update("UPDATE shopping_sessions SET state='CANCELLED' WHERE id=?",session.get("id"));
        s.jdbc().update("UPDATE cabinets SET active_session=NULL,door_open=FALSE WHERE id=?",session.get("cabinet_id"));
    }
}
