## 사용방법
---
### jetson
터미널에서 
```
sudo ufw allow from 192.168.0.7 to any port 5000 proto tcp
```
이후 main.py실행
Running on 192.168.0.12 확인

---
### watch
같은 대역 wifi로 연결
설정- 개발자모드- 디버깅 활성화

---
### PC
samsung SM-R960 연결 되었는지 확인
app실행 -> watch에 앱 깔리는지 확인

이후 jetson에서 받아오는 데이터 확인
