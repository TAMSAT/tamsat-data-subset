TAMSAT Data Subset Server Docker Image
======================================

This is a Docker image for the TAMSAT data subset server.  It contains the configuration required to deploy the TAMSAT data subset server.  It requires `/usr/local/tamsat-subset/` to be mounted as a volume, and `/usr/local/tamsat-data` (which should contain the TAMSAT data) to be mounted as a bind mount.  This can be handled by running `docker-compose` in the parent directory.

Author
------

This tool was developed by [@guygriffiths](https://github.com/guygriffiths) as part of the [TAMSAT](http://www.tamsat.org.uk) project.
