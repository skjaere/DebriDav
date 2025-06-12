select lo_unlink(lo.loid)
from (select distinct loid
      from pg_largeobject lo
               inner join (select * from pg_authid where rolname = current_user) aid on aid.rolname = current_user
               inner join (select * from pg_largeobject_metadata) md on md.lomowner = aid.oid
               left join blob on blob.local_contents = lo.loid
      where blob.id is null) lo;