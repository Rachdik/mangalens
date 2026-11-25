use IO::Socket::INET;
my $s = IO::Socket::INET->new(PeerAddr=>"127.0.0.1",PeerPort=>8888,Proto=>"tcp") or die $!;
$s->autoflush(1);
print $s "GET /manga_test_e11.jpg HTTP/1.0\r\nHost: 127.0.0.1:8888\r\n\r\n";
sleep 1;
my $buf = "";
$s->recv($buf, 1024, 0);
print $buf;
