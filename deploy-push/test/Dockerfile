# Throwaway "server" for deploy-push/test/run-test.sh: Docker-in-Docker plus an SSH daemon.
# The user "deploy" is in the docker group (no sudo), as the real server expects.
FROM docker:dind
RUN apk add --no-cache openssh-server \
    && ssh-keygen -A \
    && adduser -D -s /bin/sh deploy \
    && addgroup deploy docker \
    && sed -i 's/^deploy:!/deploy:*/' /etc/shadow \
    && printf '%s\n' 'PasswordAuthentication no' 'KbdInteractiveAuthentication no' 'PermitRootLogin no' \
         >> /etc/ssh/sshd_config
COPY entrypoint.sh /usr/local/bin/sshd-dind-entrypoint.sh
# CMD starts dockerd on its unix socket only (no TCP listener): the daemon is reachable over SSH only.
ENV DOCKER_TLS_CERTDIR=""
EXPOSE 22
ENTRYPOINT ["/usr/local/bin/sshd-dind-entrypoint.sh"]
CMD ["dockerd", "--host=unix:///var/run/docker.sock"]
