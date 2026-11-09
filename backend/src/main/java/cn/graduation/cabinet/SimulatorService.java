package cn.graduation.cabinet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.graduation.cabinet.Models.*;

/** 只处理升级前留下的旧版未完成会话。新版观测全部由小程序上报。 */
@Service
public class SimulatorService {
    private final DataStore s; private final ShoppingService shopping; private final RfidService rfid;
    public SimulatorService(DataStore s,ShoppingService shopping,RfidService rfid) {
        this.s=s;this.shopping=shopping;this.rfid=rfid;
    }
    static Map<String,Integer> held(DataStore s,String session) {
        Map<String,Integer> result=new LinkedHashMap<>();
        for(var a:s.jdbc().queryForList("SELECT product_id,direction,quantity FROM simulation_actions WHERE session_id=?",session))
            result.merge((String)a.get("product_id"),("TAKE".equals(a.get("direction"))?1:-1)*((Number)a.get("quantity")).intValue(),Integer::sum);
        return result;
    }
    @Transactional
    public Map<String,Object> returnAllAndCancel(String sessionId) {
        var session=s.lockSession(sessionId);
        var admin=new Principal("admin",true);
        if("PAID".equals(session.get("state"))) throw new BusinessException("已支付会话不能归还取消");
        if("CANCELLED".equals(session.get("state"))) return shopping.detail(sessionId,admin);
        if("OPEN".equals(session.get("state"))){
            shopping.close(sessionId,admin);
            session=s.lockSession(sessionId);
        }
        String cabinet=(String)session.get("cabinet_id");
        s.one("SELECT * FROM cabinets WHERE id=? FOR UPDATE",cabinet);
        rfid.ensure(cabinet);
        for(var e:held(s,sessionId).entrySet()){
            int quantity=e.getValue();
            if(quantity<=0) continue;
            rfid.move(cabinet,sessionId,e.getKey(),quantity,false);
            s.jdbc().update("UPDATE stocks SET on_hand=on_hand+? WHERE cabinet_id=? AND product_id=?",quantity,cabinet,e.getKey());
            s.log(cabinet,e.getKey(),"RETURN",quantity,sessionId);
        }
        shopping.finishCancel(session,(String)session.get("order_id"));
        return shopping.detail(sessionId,admin);
    }
}
