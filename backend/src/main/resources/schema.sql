-- 金额统一使用“分”的整数存储，避免double计算金额产生误差。
CREATE TABLE IF NOT EXISTS products (
 id VARCHAR(40) PRIMARY KEY, name VARCHAR(100) NOT NULL,
 price_cents INT NOT NULL, weight_grams INT NOT NULL, threshold_qty INT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS cabinets (
 id VARCHAR(40) PRIMARY KEY, name VARCHAR(100) NOT NULL, location VARCHAR(200) NOT NULL,
 door_open BOOLEAN NOT NULL DEFAULT FALSE, active_session VARCHAR(40)
);
CREATE TABLE IF NOT EXISTS stocks (
 cabinet_id VARCHAR(40) NOT NULL, product_id VARCHAR(40) NOT NULL,
 on_hand INT NOT NULL, supplied INT NOT NULL, sold INT NOT NULL DEFAULT 0,
 PRIMARY KEY(cabinet_id, product_id)
);
CREATE TABLE IF NOT EXISTS shopping_sessions (
 id VARCHAR(40) PRIMARY KEY, user_id VARCHAR(80) NOT NULL, cabinet_id VARCHAR(40) NOT NULL,
 state VARCHAR(30) NOT NULL, created_at BIGINT NOT NULL, closed_at BIGINT,
 order_id VARCHAR(40)
);
-- 仿真真值只允许SimulatorService使用，FusionEngine无法访问这张表。
CREATE TABLE IF NOT EXISTS simulation_actions (
 id VARCHAR(40) PRIMARY KEY, session_id VARCHAR(40) NOT NULL, product_id VARCHAR(40) NOT NULL,
 direction VARCHAR(10) NOT NULL, quantity INT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS perception_events (
 id VARCHAR(40) PRIMARY KEY, session_id VARCHAR(40) NOT NULL,
 signal_json TEXT NOT NULL, result_json TEXT NOT NULL,
 status VARCHAR(20) NOT NULL, resolved_product VARCHAR(40), resolved_qty INT, resolved_direction VARCHAR(10),
 resolution_note VARCHAR(500), created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
 id VARCHAR(40) PRIMARY KEY, session_id VARCHAR(40) NOT NULL UNIQUE,
 user_id VARCHAR(80) NOT NULL, cabinet_id VARCHAR(40) NOT NULL,
 state VARCHAR(30) NOT NULL, total_cents INT NOT NULL DEFAULT 0,
 items_json TEXT NOT NULL, created_at BIGINT NOT NULL, paid_at BIGINT
);
CREATE TABLE IF NOT EXISTS payments (
 id VARCHAR(40) PRIMARY KEY, order_id VARCHAR(40) NOT NULL,
 request_key VARCHAR(80) NOT NULL UNIQUE, state VARCHAR(20) NOT NULL,
 amount_cents INT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS inventory_logs (
 id VARCHAR(40) PRIMARY KEY, cabinet_id VARCHAR(40) NOT NULL, product_id VARCHAR(40) NOT NULL,
 kind VARCHAR(30) NOT NULL, quantity INT NOT NULL, reference_id VARCHAR(40), created_at BIGINT NOT NULL
);

-- EPC是单件商品的唯一编号，product_id是SKU；柜外标签保留所属购物会话。
CREATE TABLE IF NOT EXISTS rfid_items (
 epc VARCHAR(40) PRIMARY KEY, cabinet_id VARCHAR(40) NOT NULL, product_id VARCHAR(40) NOT NULL,
 location VARCHAR(20) NOT NULL, held_session VARCHAR(40)
);
CREATE TABLE IF NOT EXISTS rfid_initialized (cabinet_id VARCHAR(40) PRIMARY KEY);

-- 虚拟门锁状态与门是否关闭分开记录，兼容旧数据库，无需删除历史数据。
CREATE TABLE IF NOT EXISTS cabinet_locks (
 cabinet_id VARCHAR(40) PRIMARY KEY, locked BOOLEAN NOT NULL DEFAULT TRUE
);
