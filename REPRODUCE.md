# Bug Reproduction and Solutions [MAC]

This document tracks bugs encountered during development and their solutions.

---

## Q: Could not find a valid Docker environment

**A:** It will say it cannot find the docker env at `/var/run/docker.sock`. Check your docker endpoint address by 
```bash
docker context ls
``` 

and relink using:

```bash
sudo ln -s $HOME/.docker/run/docker.sock /var/run/docker.sock
```

---
