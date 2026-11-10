package cn.graduation.cabinet;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static cn.graduation.cabinet.Models.CabinetInput;

/** 启动时补齐缺失的默认柜体，已有柜体的库存不重置。 */
@Component
public class SeedData implements ApplicationRunner {
    private final DataStore store;
    private final AdminService admin;
    private final RfidService rfid;

    public SeedData(DataStore store, AdminService admin, RfidService rfid) {
        this.store = store;
        this.admin = admin;
        this.rfid = rfid;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (store.products().isEmpty()) {
            store.jdbc().update("""
                INSERT INTO products(id,name,price_cents,weight_grams,threshold_qty,enabled) VALUES
                ('COLA','可乐',350,330,3,TRUE),
                ('WATER','矿泉水',200,550,3,TRUE),
                ('TEA','冰红茶',400,500,3,TRUE),
                ('JUICE','橙汁',500,330,3,TRUE),
                ('BISCUIT','饼干',600,120,2,TRUE)
                """);
        }
        String[] numbers = {"一", "二", "三", "四", "五", "六"};
        String[] locations = {"图书馆一楼", "教学楼一楼", "学生宿舍区", "体育馆入口", "食堂入口", "实验楼一楼"};
        for (int i = 0; i < numbers.length; i++) {
            String id = "CAB00" + (i + 1);
            Integer count = store.jdbc().queryForObject("SELECT COUNT(*) FROM cabinets WHERE id=?", Integer.class, id);
            if (count == 0) {
                admin.createCabinet(new CabinetInput(id, "校园" + numbers[i] + "号柜", locations[i]));
            }
        }
        initializePreviouslyEmptyStock();
    }

    /** 为旧版创建时留空的库存补一次初始货，不给售罄商品自动补货。 */
    private void initializePreviouslyEmptyStock() {
        var rows = store.jdbc().queryForList("""
            SELECT st.cabinet_id, st.product_id
            FROM stocks st JOIN cabinets c ON c.id = st.cabinet_id
            WHERE st.supplied = 0 AND st.on_hand = 0 AND st.sold = 0
              AND c.active_session IS NULL
            """);
        for (var row : rows) {
            String cabinet = (String) row.get("cabinet_id");
            String product = (String) row.get("product_id");
            rfid.ensure(cabinet);
            int changed = store.jdbc().update("""
                UPDATE stocks SET on_hand = 10, supplied = 10
                WHERE cabinet_id = ? AND product_id = ?
                  AND supplied = 0 AND on_hand = 0 AND sold = 0
                """, cabinet, product);
            if (changed == 1) {
                rfid.add(cabinet, product, 10, null);
                store.log(cabinet, product, "INITIAL", 10, null);
            }
        }
    }
}
