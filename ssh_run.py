import os
import pty
import sys
import select
import subprocess

def run_ssh():
    cmd = ["ssh", "-o", "StrictHostKeyChecking=no", "-t", "kaos@g3nas.local", "sudo -S docker logs cloudstream-server"]
    
    master, slave = pty.openpty()
    proc = subprocess.Popen(
        cmd,
        stdin=slave,
        stdout=slave,
        stderr=slave,
        close_fds=True
    )
    os.close(slave)
    
    buf = b""
    ssh_password_sent = False
    sudo_password_sent = False
    
    while True:
        if proc.poll() is not None:
            try:
                while True:
                    data = os.read(master, 1024)
                    if not data:
                        break
                    sys.stdout.buffer.write(data)
                    sys.stdout.flush()
            except OSError:
                pass
            break
            
        r, w, x = select.select([master], [], [], 0.1)
        if master in r:
            try:
                data = os.read(master, 1024)
                if not data:
                    break
                sys.stdout.buffer.write(data)
                sys.stdout.flush()
                
                buf += data
                
                # Check for SSH login password prompt
                if b"password:" in buf.lower() and not ssh_password_sent:
                    os.write(master, b"root\n")
                    ssh_password_sent = True
                    buf = b""
                # Check for sudo password prompt
                elif b"password for" in buf.lower() and not sudo_password_sent:
                    os.write(master, b"root\n")
                    sudo_password_sent = True
                    buf = b""
            except OSError:
                break
                
    proc.wait()

if __name__ == "__main__":
    run_ssh()
