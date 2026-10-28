package cn.graduation.cabinet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.graduation.cabinet.Models.*;
import static cn.graduation.cabinet.DataStore.*;

/**
 * 代替实体柜和传感器的仿真器。管理员操作→真实位置变化→生成观测→送给融合器。
 * weightError、rfidMiss、rfidOverride、visionDifferent均由人设置，结果完全可重复。
 */
@Service
public class SimulatorService {
    private final DataStore s;
    private final FusionEngine fusion;
    private final JsonCodec json;
    private final ShoppingService shopping;
    public SimulatorService(DataStore s,FusionEngine fusion,JsonCodec json,ShoppingService shopping) {
        this.s=s;this.fusion=fusion;this.json=json;this.shopping=shopping;
    }
    /** 只有仿真器与库存核对使用真实持有数；融合器从不调用此方法。 */
    static Map<String,Integer> held(DataStore s,String session) {
        Map<String,Integer> result=new LinkedHashMap<>();
        for(var a:s.jdbc().queryForList("SELECT product_id,direction,quantity FROM simulation_actions WHERE session_id=?",session))
            result.merge((String)a.get("product_id"),("TAKE".equals(a.get("direction"))?1:-1)*((Number)a.get("quantity")).intValue(),Integer::sum);
        return result;
    }
    @Transactional
    public Map<String,Object> simulate(String sessionId,SimulateRequest request) {
        var session=s.lockSession(sessionId);
        if(!"OPEN".equals(session.get("state"))) throw new BusinessException("当前柜门未打开，不能取放商品");
        if(s.jdbc().queryForObject("SELECT COUNT(*) FROM simulation_actions WHERE session_id=?",Integer.class,sessionId)>=1000)
            throw new BusinessException("单次购物最多1000个操作，请关柜结算");
        Product p=s.product(request.productId());
        if("TAKE".equals(request.direction()) && !p.enabled()) throw new BusinessException("商品已下架");
        if(request.rfidOverride()!=null && !request.rfidOverride().isBlank()) s.product(request.rfidOverride());
        apply(session,request);
        return shopping.detail(sessionId,new Principal("admin",true));
    }
    private void apply(Map<String,Object> session,SimulateRequest request) {
        String sessionId=(String)session.get("id");
        String cabinet=(String)session.get("cabinet_id");
        Product p=s.product(request.productId());
        boolean take="TAKE".equals(request.direction());
        if(take) {
            int updated=s.jdbc().update("UPDATE stocks SET on_hand=on_hand-? WHERE cabinet_id=? AND product_id=? AND on_hand>=?",request.quantity(),cabinet,p.id(),request.quantity());
            if(updated!=1) throw new BusinessException("柜内库存不足");
        } else {
            if(held(s,sessionId).getOrDefault(p.id(),0)<request.quantity()) throw new BusinessException("放回数量不能超过本会话实际取出数量");
            s.jdbc().update("UPDATE stocks SET on_hand=on_hand+? WHERE cabinet_id=? AND product_id=?",request.quantity(),cabinet,p.id());
        }
        String actionId=id();
        s.jdbc().update("INSERT INTO simulation_actions VALUES(?,?,?,?,?,?)",actionId,sessionId,p.id(),request.direction(),request.quantity(),now());
        s.log(cabinet,p.id(),take?"TAKE":"RETURN",request.quantity(),actionId);
        int sign=take?-1:1;
        Map<String,Integer> tags=new LinkedHashMap<>();
        if(!request.rfidMiss()) tags.put(request.rfidOverride()==null||request.rfidOverride().isBlank()?p.id():request.rfidOverride(),sign*request.quantity());
        // 到此为止是“生成观测”；下面的引擎只收到Signal，不会收到request或actionId。
        Signal signal=new Signal(sign*p.weightGrams()*request.quantity()+request.weightError(),tags,request.visionDifferent());
        FusionResult result=fusion.analyze(signal,s.products());
        s.jdbc().update("INSERT INTO perception_events(id,session_id,signal_json,result_json,status,created_at) VALUES(?,?,?,?,?,?)",id(),sessionId,json.write(signal),json.write(result),result.status(),now());
    }
    @Transactional
    public Map<String,Object> returnAllAndCancel(String sessionId) {
        var session=s.lockSession(sessionId);
        if("PAID".equals(session.get("state"))) throw new BusinessException("已支付会话不能归还取消");
        if("CANCELLED".equals(session.get("state"))) return shopping.detail(sessionId,new Principal("admin",true));
        // 打开状态也可取消，先生成订单留痕；归还不受原来异常信号影响，统一重新产生正常信号。
        if("OPEN".equals(session.get("state"))) {
            shopping.close(sessionId,new Principal("admin",true));
            session=s.lockSession(sessionId);
        }
        for(var e:held(s,sessionId).entrySet()) {
            int remaining=e.getValue();
            while(remaining>0) {
                int quantity=Math.min(10,remaining);
                apply(session,new SimulateRequest(e.getKey(),"RETURN",quantity,0,false,null,false));
                remaining-=quantity;
            }
        }
        shopping.finishCancel(session,(String)session.get("order_id"));
        return shopping.detail(sessionId,new Principal("admin",true));
    }
}
