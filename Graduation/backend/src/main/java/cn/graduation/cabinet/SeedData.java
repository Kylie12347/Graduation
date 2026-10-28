package cn.graduation.cabinet;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 幂等初始化：首次启动放入演示数据，之后启动不重置销售记录。 */
@Component
public class SeedData implements ApplicationRunner {
    private final DataStore store;
    public SeedData(DataStore store) { this.store=store; }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if(store.jdbc().queryForObject("SELECT COUNT(*) FROM cabinets",Integer.class)>0) return;
        store.jdbc().update("INSERT INTO cabinets(id,name,location) VALUES('CAB001','校园一号柜','图书馆一楼')");
        Object[][] rows = {{"COLA","可乐",350,330,3,12},{"WATER","矿泉水",200,550,3,10},
            {"TEA","冰红茶",400,500,3,8},{"JUICE","橙汁",500,330,3,6},{"BISCUIT","饼干",600,120,2,4}};
        for(Object[] r:rows) {
            store.jdbc().update("INSERT INTO products VALUES(?,?,?,?,?,TRUE)",r[0],r[1],r[2],r[3],r[4]);
            store.jdbc().update("INSERT INTO stocks(cabinet_id,product_id,on_hand,supplied) VALUES('CAB001',?,?,?)",r[0],r[5],r[5]);
            store.log("CAB001",r[0].toString(),"INITIAL",(Integer)r[5],null);
        }
    }
}
