-- Synthetic test credentials only. The container's owner account runs Flyway.
create role care_test_app login password 'synthetic-app-password' nosuperuser nobypassrls;
