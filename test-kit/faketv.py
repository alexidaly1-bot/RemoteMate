import ast,re,sys,ssl,socket,threading,hashlib,os
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes
from cryptography import x509
D=sys.argv[1]
pool=descriptor_pool.DescriptorPool()
for f in ['polo_pb2.py','remotemessage_pb2.py']:
    src=open(D+'/'+f).read()
    raw=ast.literal_eval(re.search(r"AddSerializedFile\((b'(?:\\.|[^'\\])*')\)",src,re.S).group(1))
    fd=descriptor_pb2.FileDescriptorProto();fd.ParseFromString(raw);pool.Add(fd)
def find(name):
    for pkg in ['', 'polo.wire.protobuf.','pairing.','remote.']:
        try: return pool.FindMessageTypeByName(pkg+name)
        except KeyError: pass
    raise KeyError(name)
Outer=message_factory.GetMessageClass(find('OuterMessage'))
RM=message_factory.GetMessageClass(find('RemoteMessage'))
def ctx():
    c=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);c.load_cert_chain('tv.crt','tv.key');c.verify_mode=ssl.CERT_OPTIONAL;c.load_verify_locations(cafile=None,cadata=None) if False else None
    c.check_hostname=False
    return c
class Conn:
    def __init__(s,sock): s.s=sock;s.buf=b''
    def read(s,cls):
        while True:
            try:
                n,p=_DecodeVarint(s.buf,0)
                if len(s.buf)>=p+n:
                    m=cls();m.ParseFromString(s.buf[p:p+n]);s.buf=s.buf[p+n:];return m
            except IndexError: pass
            d=s.s.recv(65536)
            if not d: raise EOFError
            s.buf+=d
    def send(s,m):
        b=m.SerializeToString();s.s.sendall(_VarintBytes(len(b))+b)
paired=set()
def mod_exp(der):
    k=x509.load_der_x509_certificate(der).public_key().public_numbers();return k.n,k.e
def pairing(raw):
    c=ctx();c.verify_mode=ssl.CERT_OPTIONAL
    c.load_verify_locations(cadata=None) if False else None
    s=wrap(raw,c);con=Conn(s)
    client_der=s.getpeercert(True)
    m=con.read(Outer);print('PAIR <-',m.pairing_request);assert m.pairing_request.service_name=='atvremote'
    r=Outer(protocol_version=2,status=200);r.pairing_request_ack.server_name='Kogan G6';con.send(r)
    m=con.read(Outer);print('PAIR <- options',m.options);assert m.options.input_encodings[0].type==3 and m.options.input_encodings[0].symbol_length==6
    r=Outer(protocol_version=2,status=200);r.options.CopyFrom(m.options);con.send(r)
    m=con.read(Outer);print('PAIR <- config',m.configuration);assert m.configuration.client_role==1
    r=Outer(protocol_version=2,status=200);r.configuration_ack.SetInParent();con.send(r)
    cn,ce=mod_exp(client_der);sn,se=mod_exp(open('tv.crt','rb').read() and ssl.PEM_cert_to_DER_cert(open('tv.crt').read()))
    nonce=os.urandom(2);h=hashlib.sha256()
    for v in [bytes.fromhex(f"{cn:X}"),bytes.fromhex(f"0{ce:X}"),bytes.fromhex(f"{sn:X}"),bytes.fromhex(f"0{se:X}"),nonce]:h.update(v)
    dg=h.digest();code=f"{dg[0]:02X}"+nonce.hex().upper()
    open('code.txt','w').write(code);print('PAIR code shown on TV:',code,flush=True)
    m=con.read(Outer)
    ok=m.secret.secret==dg;print('PAIR secret correct:',ok)
    r=Outer(protocol_version=2,status=200 if ok else 402)
    if ok: r.secret_ack.secret=dg;paired.add(client_der)
    con.send(r);s.close()
def wrap(raw,c):
    # require a client cert but accept any (real TV checks it against paired list)
    c.verify_mode=ssl.CERT_OPTIONAL
    c.verify_flags &= ~ssl.VERIFY_X509_STRICT
    c.load_verify_locations(cafile='client.pem')
    return c.wrap_socket(raw,server_side=True)
def remote(raw):
    c=ctx();s=wrap(raw,c);con=Conn(s)
    der=s.getpeercert(True)
    if der not in paired: print('REMOTE: unknown phone, closing');s.close();return
    m=RM();m.remote_configure.code1=622;m.remote_configure.device_info.vendor='Kogan';m.remote_configure.device_info.model='G6';con.send(m)
    m=con.read(RM);print('REMOTE <- configure',m.remote_configure.code1,m.remote_configure.device_info.package_name)
    r=RM();r.remote_set_active.active=622;con.send(r)
    m=con.read(RM);print('REMOTE <- set_active',m.remote_set_active.active)
    r=RM();r.remote_start.started=True;con.send(r)
    r=RM();r.remote_ping_request.val1=42;con.send(r)
    r=RM();r.remote_set_volume_level.volume_level=17;r.remote_set_volume_level.volume_max=100;con.send(r)
    r=RM();r.remote_ime_batch_edit.ime_counter=5;r.remote_ime_batch_edit.field_counter=9;con.send(r)
    s.settimeout(6)
    try:
        while True:
            m=con.read(RM)
            f=m.WhichOneof('remote_message') if False else [d.name for d,_ in m.ListFields()][0]
            if f=='remote_key_inject':
                print('REMOTE <- key',m.remote_key_inject.key_code,m.remote_key_inject.direction)
                if m.remote_key_inject.key_code==84:
                    r=RM();r.remote_voice_begin.session_id=7;con.send(r)
            elif f=='remote_voice_payload': print('REMOTE <- voice bytes',len(m.remote_voice_payload.samples),'session',m.remote_voice_payload.session_id)
            else: print('REMOTE <-',str(m).replace('\n',' '))
    except Exception as e: print('REMOTE done',type(e).__name__)
    s.close()
def serve(port,fn):
    ls=socket.socket();ls.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);ls.bind(('127.0.0.1',port));ls.listen()
    while True:
        raw,_=ls.accept();threading.Thread(target=fn,args=(raw,),daemon=True).start()
threading.Thread(target=serve,args=(6467,pairing),daemon=True).start()
serve(6466,remote)
