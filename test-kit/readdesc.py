import ast,re,sys
from google.protobuf import descriptor_pb2
for path in sys.argv[1:]:
    src=open(path).read()
    m=re.search(r"AddSerializedFile\((b'(?:\\.|[^'\\])*')\)",src,re.S)
    raw=ast.literal_eval(m.group(1))
    fd=descriptor_pb2.FileDescriptorProto();fd.ParseFromString(raw)
    def dump(msg,ind=''):
        for f in msg.field:
            print(ind+msg.name,f.name,f.number,(f.type_name or f.type).split('.')[-1] if f.type_name else f.type,'rep' if f.label==3 else '')
        for n in msg.nested_type: dump(n,ind+'  ')
        for e in msg.enum_type: print(ind+'  enum',e.name,[(v.name,v.number) for v in e.value])
    for msg in fd.message_type: dump(msg)
    for e in fd.enum_type:
        if e.name=='RemoteKeyCode':
            want='POWER VOLUME_MUTE VOLUME_UP VOLUME_DOWN TV_INPUT CHANNEL_UP CHANNEL_DOWN MEDIA_REWIND MEDIA_PLAY_PAUSE MEDIA_FAST_FORWARD PROFILE_SWITCH SETTINGS SEARCH DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT DPAD_CENTER BACK HOME TV GUIDE DEL ENTER ALL_APPS LIVE INFO'.split()
            d={v.name[8:]:v.number for v in e.value}
            print({w:d.get(w) for w in want})
        else: print('enum',e.name,[(v.name,v.number) for v in e.value])
