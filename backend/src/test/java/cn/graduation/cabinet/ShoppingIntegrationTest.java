package cn.graduation.cabinet;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static cn.graduation.cabinet.PerceptionModels.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:cabinettest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=test-password","app.vision.key=","app.vision.model="})
@AutoConfigureMockMvc
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
class ShoppingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataStore store;
    @Autowired SeedData seed;
    @Autowired PerceptionService perception;
    @Autowired AuthService auth;
    String admin,user;
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("consumer_sessions","cabinet_audits","product_profiles","cabinet_locks","rfid_items","rfid_initialized","payments","orders","perception_events","simulation_actions","shopping_sessions","inventory_logs","stocks","products","cabinets"))
            store.jdbc().update("DELETE FROM "+table);
        seed.run(null);
        admin=call("/auth/login",null,Map.of("role","ADMIN","password","test-password")).get("token").asText();
        user=guest();
    }
    String guest() throws Exception{return call("/auth/login",null,Map.of("role","CONSUMER")).get("token").asText();}
    JsonNode call(String path,String token,Object body) throws Exception {
        var req=body==null?get("/api"+path):post("/api"+path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if(token!=null)req.header("Authorization","Bearer "+token);
        return mapper.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    String open(String cabinet) throws Exception {return call("/sessions",user,Map.of("cabinetId",cabinet)).get("id").asText();}
    int stock(String column,String cabinet,String sku){return store.jdbc().queryForObject("SELECT "+column+" FROM stocks WHERE cabinet_id=? AND product_id=?",Integer.class,cabinet,sku);}
    Report report(String session,String sku,int loss,List<Photo> photos) throws Exception{
        var baseline=call("/sessions/"+session+"/simulation",user,null);
        List<String> before=new ArrayList<>();
        baseline.get("tags").forEach(t->before.add(t.asText()));
        List<String> after=new ArrayList<>(before);
        if(sku!=null) after.remove(before.stream().filter(t->baseline.path("tagProducts").path(t).asText().equals(sku)).findFirst().orElseThrow());
        int grams=baseline.get("grams").asInt();
        return new Report(grams,grams-loss,before,after,photos);
    }
    JsonNode pay(String order,String key,boolean success) throws Exception {
        return call("/orders/"+order+"/pay",user,Map.of("requestKey",key,"success",success));
    }
    @Test void cabinetIdentityAndPublicQrAreScoped() throws Exception {
        seed.run(null);
        var cabinets = call("/cabinets", user, null);
        List<String> ids = new ArrayList<>();
        Set<String> qrCodes = new HashSet<>();
        for (var cabinet : cabinets) {
            String id = cabinet.path("id").asText();
            ids.add(id);
            assertThat(call("/cabinets/" + id + "/catalog", user, null).path("products").size()).isEqualTo(5);
            qrCodes.add(mvc.perform(get("/api/cabinets/" + id + "/qr")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        }
        assertThat(ids).containsExactly("CAB001", "CAB002", "CAB003", "CAB004", "CAB005", "CAB006");
        assertThat(qrCodes).hasSize(6);
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(12);
        var qr1=mvc.perform(get("/api/cabinets/CAB001/qr")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("image/svg+xml")).andReturn().getResponse().getContentAsString();
        var qr2=mvc.perform(get("/api/cabinets/CAB002/qr")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(qr1).startsWith("<svg").isNotEqualTo(qr2);
        String s=open("CAB002");
        assertThat(open("CAB002")).isEqualTo(s);
        assertThat(store.one("SELECT * FROM cabinets WHERE id='CAB001'").get("door_open")).isEqualTo(false);
        var a=call("/admin/sessions/"+s+"/audit",admin,null);
        assertThat(a.path("cabinetName").asText()).isEqualTo("校园二号柜");
        assertThat(a.path("before").path("tags").size()).isEqualTo(40);
        assertThat(a.has("after")).isFalse();
        mvc.perform(post("/api/sessions").header("Authorization","Bearer "+user).contentType(MediaType.APPLICATION_JSON).content("{\"cabinetId\":\"CAB001\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/sessions/"+s+"/simulation").header("Authorization","Bearer "+guest())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/sessions/"+s+"/audit").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
    }
    @Test void consensusPaysDirectlyAndPaymentRetryIsIdempotent() throws Exception {
        String s=open("CAB002");
        var observed=report(s,"COLA",332,List.of());
        var v=new VisionResult("OK",List.of(new VisionItem("可乐",1)));
        var result=perception.submit(s,auth.authenticate("Bearer "+user),observed,List.of(),v);
        String order=(String)result.get("order_id");
        assertThat(result.get("state")).isEqualTo("UNPAID");
        assertThat(stock("on_hand","CAB002","COLA")).isEqualTo(11);
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(12);
        assertThat(perception.submit(s,auth.authenticate("Bearer "+user),observed,List.of(),v).get("order_id")).isEqualTo(order);
        assertThat(pay(order,"failed",false).path("state").asText()).isEqualTo("UNPAID");
        assertThat(pay(order,"success",true).path("checkoutRoute").asText()).isEqualTo("DIRECT");
        pay(order,"success",true);
        assertThat(stock("sold","CAB002","COLA")).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM payments",Integer.class)).isEqualTo(2);
        assertThat(store.one("SELECT locked FROM cabinet_locks WHERE cabinet_id='CAB002'").get("locked")).isEqualTo(true);
        var zero=call("/admin/dashboard?cabinetId=CAB001",admin,null);
        assertThat(zero.path("revenueCents").asInt()).isZero();
        assertThat(zero.path("comparison").size()).isEqualTo(6);
        assertThat(call("/admin/orders?cabinetId=CAB002&route=DIRECT&state=PAID",admin,null).size()).isEqualTo(1);
        assertThat(call("/admin/orders?cabinetId=CAB001&route=DIRECT&state=PAID",admin,null).size()).isZero();
    }
    @Test void sameWeightConflictIsReviewedByOperationsBeforePayment() throws Exception {
        String s=open("CAB001");
        var observation=report(s,"COLA",330,List.of());
        var v=new VisionResult("OK",List.of(new VisionItem("橙汁",1)));
        var closed=perception.submit(s,auth.authenticate("Bearer "+user),observation,List.of(),v);
        String order=(String)closed.get("order_id");
        assertThat(closed.get("state")).isEqualTo("REVIEW");
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(12);
        var adminPath="/api/admin/sessions/"+s+"/review";
        var body=mapper.writeValueAsString(new Review(List.of(new Line("JUICE",1)),"视觉序列中实际为橙汁"));
        mvc.perform(post(adminPath).header("Authorization","Bearer "+user).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/"+order+"/confirm").header("Authorization","Bearer "+user)).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/"+order+"/pay").header("Authorization","Bearer "+user).contentType(MediaType.APPLICATION_JSON).content("{\"requestKey\":\"before-review\",\"success\":true}")).andExpect(status().isConflict());
        var audit=call("/admin/sessions/"+s+"/audit",admin,null);
        assertThat(audit.path("after").path("weightCandidates").size()).isEqualTo(2);
        assertThat(audit.path("after").path("rfidItems").path("COLA").asInt()).isEqualTo(1);
        assertThat(audit.path("after").path("vision").path("items").get(0).path("name").asText()).isEqualTo("橙汁");
        call("/admin/sessions/"+s+"/review",admin,new Review(List.of(new Line("JUICE",1)),"视觉序列中实际为橙汁"));
        var paid=pay(order,"reviewed",true);
        assertThat(paid.path("checkoutRoute").asText()).isEqualTo("ADMIN_REVIEW");
        assertThat(paid.path("total_cents").asInt()).isEqualTo(500);
        assertThat(stock("on_hand","CAB001","JUICE")).isEqualTo(5);
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(12);
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM rfid_items WHERE product_id='JUICE' AND location='SOLD'",Integer.class)).isEqualTo(1);
    }
    @Test void missingModelDoesNotInventVisualResultAndPhotosAreOrdered() throws Exception {
        String s=open("CAB001");
        String png="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/qv8AAAAASUVORK5CYII=";
        var report=report(s,"TEA",500,List.of(new Photo(2,png),new Photo(1,png)));
        var closed=call("/sessions/"+s+"/perception",user,report);
        assertThat(closed.path("state").asText()).isEqualTo("REVIEW");
        assertThat(closed.has("after")).isFalse();
        var after=call("/admin/sessions/"+s+"/audit",admin,null).path("after");
        assertThat(after.path("vision").path("status").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(after.path("photos").get(0).path("timestamp").asLong()).isEqualTo(1);
        call("/admin/sessions/"+s+"/review",admin,new Review(List.of(),"未确认商品，本次演示作废"));
        assertThat(call("/sessions/"+s,user,null).path("state").asText()).isEqualTo("CANCELLED");
        assertThat(store.one("SELECT * FROM cabinets WHERE id='CAB001'").get("active_session")).isNull();
    }
    @Test void explicitSpecialProductCanUseUniqueWeightAndKeepsProductImage() throws Exception {
        var p=Map.of("id","SPECIAL","name","不规则重量样品","priceCents",850,"weightGrams",713,"thresholdQty",1,"enabled",true,
            "weightPriority",true,"imageUrl","https://example.test/sample.jpg");
        call("/admin/products",admin,p);
        call("/admin/restock",admin,Map.of("cabinetId","CAB001","productId","SPECIAL","quantity",2));
        String s=open("CAB001");
        var r=call("/sessions/"+s+"/perception",user,report(s,null,721,List.of()));
        assertThat(r.path("state").asText()).isEqualTo("UNPAID");
        assertThat(r.path("order").path("checkoutRoute").asText()).isEqualTo("WEIGHT_PRIORITY");
        assertThat(r.path("order").path("items").get(0).path("imageUrl").asText()).isEqualTo("https://example.test/sample.jpg");
    }
    @Test void foreignRfidCannotBeSubmittedAndAbandonDoesNotTouchStock() throws Exception {
        var tags=call("/admin/rfid-items?cabinetId=CAB002",admin,null);
        String foreign=tags.get(0).path("epc").asText();
        String s=open("CAB001");
        String body=mapper.writeValueAsString(new Report(330,0,List.of(foreign),List.of(),List.of()));
        mvc.perform(post("/api/sessions/"+s+"/perception").header("Authorization","Bearer "+user).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(call("/sessions/"+s+"/abandon",user,Map.of()).path("state").asText()).isEqualTo("CANCELLED");
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(12);
        assertThat(call("/admin/sessions/"+s+"/audit",admin,null).has("after")).isFalse();
    }
    @Test void confirmedReturnCancelsUnpaidOrderAndRestoresStock() throws Exception {
        String s=open("CAB001");
        var v=new VisionResult("OK",List.of(new VisionItem("可乐",1)));
        var submitted=perception.submit(s,auth.authenticate("Bearer "+user),report(s,"COLA",330,List.of()),List.of(),v);
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(11);
        var cancelled=call("/admin/sessions/"+s+"/cancel-unpaid",admin,new CancelReview("已核实商品放回柜中"));
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(stock("on_hand","CAB001","COLA")).isEqualTo(12);
        assertThat(stock("sold","CAB001","COLA")).isZero();
        assertThat(cancelled.path("order").path("id").asText()).isEqualTo(submitted.get("order_id"));
    }
    @Test void sameConsumerCannotOpenTwoCabinetsConcurrently() throws Exception {
        var principal=auth.authenticate("Bearer "+user);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var jobs=List.<Callable<Boolean>>of(
                ()->{try{perception.open("CAB001",principal);return true;}catch(BusinessException e){return false;}},
                ()->{try{perception.open("CAB002",principal);return true;}catch(BusinessException e){return false;}});
            var results=pool.invokeAll(jobs);
            assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
        } finally {pool.shutdownNow();}
    }
}
