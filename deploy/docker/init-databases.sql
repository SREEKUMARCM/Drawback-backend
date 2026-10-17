-- one database per service: services never share tables (they share events and gRPC contracts only)
CREATE DATABASE auth_db;
CREATE DATABASE review_db;
CREATE DATABASE notification_db;
