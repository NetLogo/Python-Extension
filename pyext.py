#!/usr/bin/env python

import sys
import os
import socket
import sys
import numbers
import json
from collections import Mapping
import umsgpack
import traceback


LEN_SIZE = 10
TYPE_SIZE = 1

# In
STMT_MSG = 0
EXPR_MSG = 1

# Out

SUCC_MSG = 0
ERR_MSG  = 1

class ConnectionReader(object):
    def __init__(self, conn):
        self.conn = conn
        self.buff = bytearray()

    def read(self, size):
        while len(self.buff) < size:
            data = self.conn.recv(8192) # Default buffer size in messagepack
            if len(data) == 0:
                raise EOFError('Connection closed')
            self.buff.extend(data)
        result = bytes(self.buff[:size])
        del self.buff[:size]
        return result

class ConnectionWriter(object):
    def __init__(self, conn):
        self.conn = conn
        self.buff = bytearray()

    def write(self, data):
        self.buff.extend(data)

    def flush(self):
        self.conn.sendall(self.buff)
        self.clear()

    def clear(self):
        self.buff = bytearray()

def utf8(bs):
    if sys.version_info >= (3,0):
        return str(bs, 'UTF8')
    else:
        return unicode(bs, 'UTF8')

def to_bytes(s):
    if sys.version_info >= (3,0):
        return bytes(s, 'UTF8')
    else:
        return bytes(s)

def sanitize(x):
    # Note: no `unicode` class in Python 3, so we have to check string
    if isinstance(x, float) or \
       isinstance(x, int) or \
       isinstance(x, bool) or \
       isinstance(x, str) or \
       isinstance(x, bytes) or \
       x.__class__.__name__ == 'unicode' or \
       isinstance(x, None.__class__):
        return x
    elif isinstance(x, numbers.Number):
        return float(x)
    elif isinstance(x, Mapping):
        return {sanitize(k): sanitize(v) for k,v in x.items()}
    elif hasattr(x, '__len__'):
        return [sanitize(y) for y in x]
    else:
        return repr(x)


def send(f, *args):
    try:
        sargs = [ sanitize(arg) for arg in args ]
        for arg in sargs:
            umsgpack.pack(arg, f)
        f.flush()
    except Exception as e:
        f.clear()
        raise

def logo_responder(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(('localhost', port))
        sock.listen(0)
        conn, addr = sock.accept()
        buff = bytes()
        try:
            inp = ConnectionReader(conn)
            out = ConnectionWriter(conn)
            globs = {}
            while True:
                msg_type = umsgpack.unpack(inp)
                try:
                    if msg_type == STMT_MSG:
                        code = umsgpack.unpack(inp)
                        exec(code, globs)
                        send(out, SUCC_MSG)
                    elif msg_type == EXPR_MSG:
                        code = umsgpack.unpack(inp)
                        result = eval(code, globs)
                        send(out, SUCC_MSG, result)
                    else:
                        raise Exception('Unrecognized message type: {}'.format(msg_type))
                except Exception as e:
                    send(out, ERR_MSG, str(e), traceback.format_exc())
                finally:
                    flush()
        finally:
            conn.close()
    finally:
        sock.close()

def flush():
    sys.stdout.flush()
    sys.stderr.flush()

def conn_iter(conn):
    buff = bytes()


if __name__ == '__main__':
    sys.path.insert(0, os.getcwd())
    logo_responder(int(sys.argv[-1]))


