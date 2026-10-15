-- Explicitly enabled only through local/test Flyway locations. All values are
-- fictional, deterministic demonstration data. Dates are UTC and fixed in 2099.
INSERT INTO venue(id,name,city,address) VALUES(1,'SeatFlow Demo Theatre','Shanghai','Fictional Demo Street 1');
INSERT INTO hall(id,venue_id,name,layout_version) VALUES(1,1,'Main Hall',1);
INSERT INTO show_catalog(id,title,description,duration_minutes) VALUES(1,'SeatFlow Live','Fictional show for local demonstrations',100);
-- Demonstration password for both users: demo-seatflow (BCrypt cost 10).
INSERT INTO user_account(id,username,password_hash,role,enabled,created_at) VALUES
 (1,'demo','$2b$10$abcdefghijklmnopqrstuuhOo4oKZEkBMUSgALsFr4RA5JcoLcYUS','USER',TRUE,'2026-01-01 00:00:00'),
 (2,'admin','$2b$10$abcdefghijklmnopqrstuuhOo4oKZEkBMUSgALsFr4RA5JcoLcYUS','ADMIN',TRUE,'2026-01-01 00:00:00');
INSERT INTO show_session(id,show_id,hall_id,starts_at,sales_start_at,sales_end_at,status,layout_version) VALUES
 (101,1,1,'2099-12-01 11:00:00','2026-01-01 00:00:00','2099-12-01 11:00:00','ON_SALE',1),
 (102,1,1,'2099-12-02 11:00:00','2026-01-01 00:00:00','2099-12-02 11:00:00','ON_SALE',1);
INSERT INTO hall_seat(id,hall_id,row_no,seat_no,position,block_id,price_zone,x,y,enabled) VALUES
 (1,1,'A','1',1,'LEFT','PREMIUM',1,1,TRUE),
 (2,1,'A','2',2,'LEFT','PREMIUM',2,1,TRUE),
 (3,1,'A','3',3,'LEFT','PREMIUM',3,1,TRUE),
 (4,1,'A','4',4,'LEFT','PREMIUM',4,1,TRUE),
 (5,1,'A','5',5,'RIGHT','PREMIUM',6,1,TRUE),
 (6,1,'A','6',6,'RIGHT','PREMIUM',7,1,TRUE),
 (7,1,'A','7',7,'RIGHT','PREMIUM',8,1,TRUE),
 (8,1,'A','8',8,'RIGHT','PREMIUM',9,1,TRUE),
 (9,1,'B','1',1,'LEFT','STANDARD',1,2,TRUE),
 (10,1,'B','2',2,'LEFT','STANDARD',2,2,FALSE),
 (11,1,'B','3',3,'LEFT','STANDARD',3,2,TRUE),
 (12,1,'B','4',4,'LEFT','STANDARD',4,2,TRUE),
 (13,1,'B','5',5,'RIGHT','STANDARD',6,2,TRUE),
 (14,1,'B','6',6,'RIGHT','STANDARD',7,2,TRUE),
 (15,1,'B','7',7,'RIGHT','STANDARD',8,2,TRUE),
 (16,1,'B','8',8,'RIGHT','STANDARD',9,2,TRUE);
INSERT INTO session_seat(session_id,seat_id,row_no,seat_no,position,block_id,price_zone,x,y,price,status)
 SELECT ss.id,hs.id,hs.row_no,hs.seat_no,hs.position,hs.block_id,hs.price_zone,hs.x,hs.y,
 CASE hs.price_zone WHEN 'PREMIUM' THEN 280.00 ELSE 180.00 END,
 CASE hs.enabled WHEN TRUE THEN 'AVAILABLE' ELSE 'DISABLED' END
 FROM show_session ss JOIN hall_seat hs ON hs.hall_id=ss.hall_id;
