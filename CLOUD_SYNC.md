# Cloud sync behavior

BeeKeep remains offline-first.

1. Every local write lands in Room first.
2. A sync outbox records the changed entity.
3. WorkManager syncs when a network is available.
4. Realtime is an accelerator: a cloud document change triggers a refresh.
5. Inspection photos stay on-device until they upload successfully to the private `inspection-photos` bucket.
6. Photo paths are namespaced under the authenticated user ID and hive ID.

Run `supabase/migrations/20261006_beekeep_cloud_v2.sql` in the Supabase SQL editor. Realtime is enabled for `beekeep_documents`, and storage RLS limits file operations to each user's own folder.
