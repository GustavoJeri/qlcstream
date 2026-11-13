#!/bin/sh
# Only a newly created media destination receives ownership and permissions.
chown 100:101 /secrets && chmod 700 /secrets || echo 'Protected directory needs administrator attention' >&2
if [ ! -e /media/incoming ] && [ ! -L /media/incoming ]; then
    mkdir -m 770 /media/incoming &&
        chown 100:1000 /media/incoming &&
        chmod 2770 /media/incoming || echo 'Download directory needs administrator attention' >&2
fi
exit 0
