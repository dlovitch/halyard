#!/bin/sh

echo '#!/usr/bin/env bash' | sudo tee /usr/local/bin/hal > /dev/null
echo '/opt/halyard/bin/hal "$@"' | sudo tee /usr/local/bin/hal > /dev/null

chmod +x /usr/local/bin/hal

install --mode=755 --owner=spinnaker --group=spinnaker --directory  /var/log/spinnaker/halyard 

service halyard restart
