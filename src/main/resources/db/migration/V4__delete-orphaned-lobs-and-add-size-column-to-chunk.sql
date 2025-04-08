select lo_unlink(lo.loid)
from (select distinct loid
      from pg_largeobject lo
               left join blob on blob.local_contents = lo.loid
      where blob.id is null) lo;

ALTER TABLE blob
    ADD COLUMN size integer;