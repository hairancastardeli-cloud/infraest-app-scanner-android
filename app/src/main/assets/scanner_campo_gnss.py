#!/usr/bin/env python3
# Auxiliar do GNSS do Scanner Campo (roda NO SCANNER, em /tmp: some ao reiniciar).
# Com o levantamento parado o receptor (Unicore, COM1 = /dev/ttyS1 a 460800) fica mudo: so o programa "pos" o configura.
# Este auxiliar, enquanto o "pos" NAO roda: pede o GGA/GST ao receptor, manda as linhas NMEA ao app (TCP 8081) e
# escreve no receptor a correcao RTCM que o app manda. Quando o "pos" aparece (levantamento iniciado), solta a serial na hora.
# Sai sozinho: "#FIM" do app, ou 5 min sem o app conectado. Ao sair deixa o receptor mudo de novo (UNLOGALL COM1).
# IMU: /dev/ttyS0 a 115200 transmite sempre (200 Hz); so e lida aqui com o "pos" parado. Quadro de 40 bytes:
#   55 AA 24 | int32 LE x9: ms, giro x/y/z, acel x/y/z (um/s2), temperatura x100, tempo 0,1 ms | soma(24..dados) & FF
# Vai ao app a cada 0,1 s: "#IMU ms n gx gy gz ax ay az temp" (giro = soma de giro*0,005 s no intervalo; acel = media).
import os, select, signal, socket, struct, termios, time, tty

SERIAL = os.environ.get('SCG_SERIAL', '/dev/ttyS1')                 # SCG_*: so para o teste no PC (serial simulada)
IMU = os.environ.get('SCG_IMU', '/dev/ttyS0')
PORTA = int(os.environ.get('SCG_PORTA', '8081'))
PROG_LEVANTAMENTO = os.environ.get('SCG_POS', 'pos')
CMDS = b'UNLOGALL COM1\r\nLOG COM1 GPGGA ONTIME 1\r\nLOG COM1 GPGST ONTIME 1\r\n'
SEM_APP_S = int(os.environ.get('SCG_SEM_APP_S', '300'))


def pos_rodando():
    for p in os.listdir('/proc'):
        if p.isdigit():
            try:
                with open('/proc/%s/comm' % p) as f:
                    if f.read().strip() == PROG_LEVANTAMENTO:
                        return True
            except OSError:
                pass
    return False


def abrir():
    fd = os.open(SERIAL, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    tty.setraw(fd)
    a = termios.tcgetattr(fd)
    a[4] = a[5] = termios.B460800
    a[2] |= termios.CLOCAL | termios.CREAD
    termios.tcsetattr(fd, termios.TCSANOW, a)
    os.write(fd, CMDS)
    return fd


def abrir_imu():
    fd = os.open(IMU, os.O_RDONLY | os.O_NOCTTY | os.O_NONBLOCK)
    tty.setraw(fd)
    a = termios.tcgetattr(fd)
    a[4] = a[5] = termios.B115200
    a[2] |= termios.CLOCAL | termios.CREAD
    termios.tcsetattr(fd, termios.TCSANOW, a)
    return fd


class Imu:
    """Remonta os quadros da IMU e junta 0,1 s num resumo."""

    def __init__(self):
        self.buf = b''
        self.zerar()
        self.ruins = 0

    def zerar(self):
        self.n = 0
        self.g = [0.0, 0.0, 0.0]
        self.a = [0, 0, 0]
        self.ms = 0
        self.temp = 0

    def alimentar(self, d):
        self.buf += d
        while True:
            i = self.buf.find(b'\x55\xaa\x24')
            if i < 0:
                self.buf = self.buf[-2:]
                return
            if len(self.buf) - i < 40:
                self.buf = self.buf[i:]
                return
            q = self.buf[i:i + 40]
            if sum(q[2:39]) & 0xFF != q[39]:
                self.ruins += 1
                self.buf = self.buf[i + 1:]
                continue
            v = struct.unpack('<9i', q[3:39])
            self.n += 1
            for k in range(3):
                self.g[k] += v[1 + k] * 0.005
                self.a[k] += v[4 + k]
            self.ms, self.temp = v[0], v[7]
            self.buf = self.buf[i + 40:]

    def resumo(self):
        if self.n == 0:
            return None
        n = self.n
        s = '#IMU %d %d %.1f %.1f %.1f %d %d %d %d\n' % (self.ms, n, self.g[0], self.g[1], self.g[2],
                                                          self.a[0] // n, self.a[1] // n, self.a[2] // n, self.temp)
        self.zerar()
        return s.encode()


class Aux:
    def __init__(self):
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(('0.0.0.0', PORTA))
        self.srv.listen(1)
        self.cli = None
        self.fd = None
        self.buf = b''
        self.ult_app = time.time()
        self.ult_rx = 0.0
        self.ult_cmd = 0.0
        self.ult_est = 0.0
        self.fim = False
        self.erro = b''
        self.fd_imu = None
        self.imu = Imu()
        self.ult_imu_tent = 0.0
        self.ult_imu_env = 0.0

    def enviar(self, b):
        if self.cli is None:
            return
        try:
            self.cli.sendall(b)
        except OSError:
            self.fechar_cli()

    def fechar_cli(self):
        try:
            self.cli.close()
        except Exception:
            pass
        self.cli = None
        self.ult_app = time.time()

    def soltar_serial(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
            self.buf = b''
        if self.fd_imu is not None:
            os.close(self.fd_imu)
            self.fd_imu = None
            self.imu = Imu()

    def passo(self):
        agora = time.time()
        if pos_rodando():
            self.soltar_serial()
            estado = b'#POS\n'
        else:
            if self.fd is None and agora - self.ult_cmd > 5:
                try:
                    self.fd = abrir()
                    self.erro = b''
                except OSError as e:                                         # tenta de novo a cada 5 s
                    self.erro = ('#ERRO serial %s: %s\n' % (SERIAL, e)).encode()
                self.ult_cmd = agora
            elif self.fd is not None and agora - self.ult_rx > 5 and agora - self.ult_cmd > 5:   # receptor calado: pede de novo
                os.write(self.fd, CMDS)
                self.ult_cmd = agora
            estado = b'#OK\n' if self.fd is not None else (self.erro or b'#ABRINDO\n')
            if self.fd_imu is None and agora - self.ult_imu_tent > 5:              # IMU: so leitura, tenta a cada 5 s
                self.ult_imu_tent = agora
                try:
                    self.fd_imu = abrir_imu()
                except OSError:
                    pass
        if agora - self.ult_est >= 1:
            self.enviar(estado)
            self.ult_est = agora
        if agora - self.ult_imu_env >= 0.1:
            s = self.imu.resumo()
            if s:
                self.enviar(s)
            self.ult_imu_env = agora
        lst = [self.srv] + ([self.cli] if self.cli else []) + ([self.fd] if self.fd is not None else []) + \
            ([self.fd_imu] if self.fd_imu is not None else [])
        r, _, _ = select.select(lst, [], [], 0.05)
        for x in r:
            if x is self.srv:
                c, _ = self.srv.accept()
                c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                if self.cli:
                    self.fechar_cli()
                self.cli = c
            elif x is self.cli:
                try:
                    d = self.cli.recv(8192)
                except OSError:
                    d = b''
                if not d:
                    self.fechar_cli()
                elif d.startswith(b'#FIM'):
                    self.fim = True
                elif self.fd is not None:
                    os.write(self.fd, d)                                     # correcao RTCM do caster
            elif self.fd_imu is not None and x == self.fd_imu:
                try:
                    self.imu.alimentar(os.read(self.fd_imu, 8192))
                except BlockingIOError:
                    pass
            elif x == self.fd:
                try:
                    d = os.read(self.fd, 8192)
                except BlockingIOError:
                    d = b''
                if d:
                    self.ult_rx = agora
                    self.buf += d
                    # so as linhas de texto (NMEA e respostas "$command"); binario fica de fora
                    while b'\n' in self.buf:
                        linha, self.buf = self.buf.split(b'\n', 1)
                        i = linha.find(b'\x24')
                        if i >= 0:
                            self.enviar(linha[i:].rstrip(b'\r') + b'\n')
                    if len(self.buf) > 4096:
                        self.buf = b''
        if self.cli is None and agora - self.ult_app > SEM_APP_S:
            self.fim = True

    def sair(self):
        if self.fd is not None:
            try:
                os.write(self.fd, b'UNLOGALL COM1\r\n')
            except OSError:
                pass
        self.soltar_serial()
        if self.cli:
            self.fechar_cli()
        self.srv.close()


def main():
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    a = Aux()
    signal.signal(signal.SIGTERM, lambda *_: setattr(a, 'fim', True))
    try:
        while not a.fim:
            a.passo()
    finally:
        a.sair()


if __name__ == '__main__':
    main()
