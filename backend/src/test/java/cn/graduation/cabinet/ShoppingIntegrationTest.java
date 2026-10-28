package cn.graduation.cabinet;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static cn.graduation.cabinet.Models.*;

/** 使用独立内存库，测试不会修改演示用文件数据库。MockMvc走真实HTTP参数校验/鉴权/事务。 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:cabinettest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "app.admin-password=test-password"})
@AutoConfigureMockMvc
class ShoppingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataStore store;
    @Autowired SeedData seed;
    @Autowired ShoppingService shopping;
    String admin,user;
    @BeforeEach void reset() throws Exception {
        // 表名来自固定测试代码，不接受外部输入；仅清理本测试的内存库。
        for(String table:List.of("rfid_items","rfid_initialized","payments","orders","perception_events","simulation_actions","shopping_sessions","inventory_logs","stocks","products","cabinets")) store.jdbc().update("DELETE FROM "+table);
        seed.run(null);
        admin=call("/auth/login",null,Map.of("role","ADMIN","password","test-password")).get("token").asText();
        user=call("/auth/login",null,Map.of("role","CONSUMER")).get("token").asText();
    }
    JsonNode call(String path,String token,Object body) throws Exception {
        var req=body==null?get("/api"+path):post("/api"+path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if(token!=null)req.header("Authorization","Bearer "+token);
        String text=mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(text);
    }
    String open() throws Exception {return call("/sessions",user,Map.of("cabinetId","CAB001")).get("id").asText();}
    JsonNode sim(String id,String sku,String dir,int qty,boolean miss,boolean visual) throws Exception {
        return call("/admin/sessions/"+id+"/simulate",admin,Map.of("productId",sku,"direction",dir,"quantity",qty,"weightError",0,"rfidMiss",miss,"visionDifferent",visual));
    }
    int stock(String column,String sku) {return store.jdbc().queryForObject("SELECT "+column+" FROM stocks WHERE cabinet_id='CAB001' AND product_id=?",Integer.class,sku);}
    @Test void completeFlowIncludesReturnFailedPaymentRetryAndIdempotency() throws Exception {
        String session=open();
        sim(session,"COLA","TAKE",2,false,false);
        sim(session,"COLA","RETURN",1,false,false);
        assertThat(stock("on_hand","COLA")).isEqualTo(11);
        assertThat(stock("sold","COLA")).isZero();
        String order=call("/sessions/"+session+"/close",user,Map.of()).get("order_id").asText();
        assertThat(call("/orders/"+order+"/confirm",user,Map.of()).get("total_cents").asInt()).isEqualTo(350);
        call("/orders/"+order+"/pay",user,Map.of("requestKey","attempt1","success",false));
        assertThat(stock("sold","COLA")).isZero();
        call("/orders/"+order+"/pay",user,Map.of("requestKey","attempt2","success",true));
        call("/orders/"+order+"/pay",user,Map.of("requestKey","attempt2","success",true));
        call("/orders/"+order+"/pay",user,Map.of("requestKey","attempt3","success",true));
        assertThat(stock("on_hand","COLA")).isEqualTo(11);
        assertThat(stock("sold","COLA")).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM payments",Integer.class)).isEqualTo(2);
        var dashboard=call("/admin/dashboard",admin,null);
        assertThat(dashboard.get("revenueCents").asInt()).isEqualTo(350);
        assertThat(dashboard.get("paidOrders").asInt()).isEqualTo(1);
        assertThat(dashboard.get("soldQuantity").asInt()).isEqualTo(1);
        assertThat(store.one("SELECT * FROM cabinets WHERE id='CAB001'").get("active_session")).isNull();
    }
    @Test void pendingRequiresManualResolutionAndKeepsOriginalEvidence() throws Exception {
        String session=open();
        var detail=sim(session,"COLA","TAKE",1,true,false);
        String event=detail.get("events").get(0).get("id").asText();
        String original=(String)store.one("SELECT * FROM perception_events WHERE id=?",event).get("result_json");
        String order=call("/sessions/"+session+"/close",user,Map.of()).get("order_id").asText();
        mvc.perform(post("/api/orders/"+order+"/confirm").header("Authorization","Bearer "+user)).andExpect(status().isConflict());
        call("/sessions/"+session+"/events/"+event+"/resolve",user,Map.of("productId","COLA","quantity",1,"direction","TAKE","note","核实为可乐一瓶"));
        assertThat(store.one("SELECT * FROM perception_events WHERE id=?",event).get("result_json")).isEqualTo(original);
        assertThat(call("/orders/"+order+"/confirm",user,Map.of()).get("total_cents").asInt()).isEqualTo(350);
    }
    @Test void cancellationRequiresPhysicalReturnAndRestoresInventory() throws Exception {
        String session=open();sim(session,"WATER","TAKE",2,false,true);
        String order=call("/sessions/"+session+"/close",user,Map.of()).get("order_id").asText();
        mvc.perform(post("/api/orders/"+order+"/cancel").header("Authorization","Bearer "+user)).andExpect(status().isConflict());
        var cancelled=call("/admin/sessions/"+session+"/return-and-cancel",admin,Map.of());
        assertThat(cancelled.get("state").asText()).isEqualTo("CANCELLED");
        assertThat(stock("on_hand","WATER")).isEqualTo(10);
        assertThat(stock("sold","WATER")).isZero();
        assertThat(call("/admin/dashboard",admin,null).get("revenueCents").asInt()).isZero();
    }
    @Test void consumerCannotSimulateOrReadAnotherUsersSession() throws Exception {
        String session=open();
        mvc.perform(get("/api/admin/stocks").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
        String other=call("/auth/login",null,Map.of("role","CONSUMER")).get("token").asText();
        mvc.perform(get("/api/sessions/"+session).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/cabinets")).andExpect(status().isUnauthorized());
    }
    @Test void rejectsOverReturnNegativeQuantitiesAndClosedDoorOperations() throws Exception {
        String session=open();
        var invalid=Map.of("productId","COLA","direction","TAKE","quantity",0,"weightError",0);
        mvc.perform(post("/api/admin/sessions/"+session+"/simulate").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        var over=Map.of("productId","COLA","direction","RETURN","quantity",1,"weightError",0);
        mvc.perform(post("/api/admin/sessions/"+session+"/simulate").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(over))).andExpect(status().isConflict());
        call("/sessions/"+session+"/close",user,Map.of());
        mvc.perform(post("/api/admin/sessions/"+session+"/simulate").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(over))).andExpect(status().isConflict());
    }
    @Test void lowStockAndRestockHaveTraceableQuantities() throws Exception {
        String session=open();sim(session,"BISCUIT","TAKE",2,false,false);
        assertThat(call("/admin/dashboard",admin,null).get("lowStock").size()).isEqualTo(1);
        call("/admin/sessions/"+session+"/return-and-cancel",admin,Map.of());
        call("/admin/restock",admin,Map.of("cabinetId","CAB001","productId","BISCUIT","quantity",10));
        assertThat(stock("on_hand","BISCUIT")).isEqualTo(14);
        assertThat(stock("supplied","BISCUIT")).isEqualTo(14);
    }
    @Test void uniqueTagsRoundTripRestockAndSnapshotEvidence() throws Exception {
        var initial=call("/admin/rfid-items?cabinetId=CAB001",admin,null);
        assertThat(initial.size()).isEqualTo(40);
        String session=open();
        sim(session,"COLA","TAKE",1,false,false);
        var outside=store.jdbc().queryForList("SELECT epc FROM rfid_items WHERE held_session=?",session);
        assertThat(outside).hasSize(1);
        String epc=(String)outside.get(0).get("epc");
        var event=store.one("SELECT * FROM perception_events WHERE session_id=?",session);
        JsonNode signal=mapper.readTree((String)event.get("signal_json"));
        assertThat(signal.get("rfid").get("beforeTags").toString()).contains(epc);
        assertThat(signal.get("rfid").get("afterTags").toString()).doesNotContain(epc);
        assertThat(signal.get("rfidDelta").get("COLA").asInt()).isEqualTo(-1);
        sim(session,"COLA","RETURN",1,false,false);
        assertThat(store.one("SELECT * FROM rfid_items WHERE epc=?",epc).get("location")).isEqualTo("IN_CABINET");
        call("/admin/sessions/"+session+"/return-and-cancel",admin,Map.of());
        call("/admin/restock",admin,Map.of("cabinetId","CAB001","productId","COLA","quantity",3));
        assertThat(call("/admin/rfid-items?cabinetId=CAB001",admin,null).size()).isEqualTo(43);
    }
    @Test void missedReadAndWrongMappingNeverChangePhysicalTagBinding() throws Exception {
        String session=open();
        sim(session,"COLA","TAKE",1,true,false);
        var row=store.one("SELECT * FROM perception_events WHERE session_id=?",session);
        JsonNode signal=mapper.readTree((String)row.get("signal_json"));
        assertThat(signal.get("rfidDelta").size()).isZero();
        assertThat(signal.get("rfid").get("beforeTags")).isEqualTo(signal.get("rfid").get("afterTags"));
        call("/admin/sessions/"+session+"/simulate",admin,Map.of("productId","WATER","direction","TAKE","quantity",1,"rfidOverride","COLA"));
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM rfid_items WHERE product_id='WATER' AND held_session=?",Integer.class,session)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM perception_events WHERE session_id=? AND status='PENDING'",Integer.class,session)).isEqualTo(2);
        call("/admin/sessions/"+session+"/return-and-cancel",admin,Map.of());
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM rfid_items WHERE location='OUTSIDE'",Integer.class)).isZero();
    }
    @Test void migratesExistingActiveSessionWithoutLosingHeldItems() throws Exception {
        String session=open();
        store.jdbc().update("UPDATE stocks SET on_hand=on_hand-2 WHERE cabinet_id='CAB001' AND product_id='COLA'");
        store.jdbc().update("INSERT INTO simulation_actions VALUES(?,?,?,?,?,?)","legacy",session,"COLA","TAKE",2,1L);
        assertThat(call("/admin/rfid-items?cabinetId=CAB001",admin,null).size()).isEqualTo(40);
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM rfid_items WHERE held_session=?",Integer.class,session)).isEqualTo(2);
        sim(session,"COLA","RETURN",2,false,false);
        assertThat(stock("on_hand","COLA")).isEqualTo(12);
        assertThat(call("/admin/rfid-items?cabinetId=CAB001",admin,null).size()).isEqualTo(40);
    }
    @Test void concurrentOpenOnlyAllowsOneConsumer() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) {
                Principal p=new Principal("concurrent"+i,false);
                results.add(pool.submit(()->{ready.await();try{shopping.open("CAB001",p);return true;}catch(BusinessException e){return false;}}));
            }
            ready.countDown();
            int success=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))success++;
            assertThat(success).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
}
