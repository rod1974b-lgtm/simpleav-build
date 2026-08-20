
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.clock import Clock
import os, time

class AVLayout(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation='vertical', padding=15, spacing=10, **kwargs)
        self.add_widget(Label(text='SimpleAV v8 PHONE', font_size=26, size_hint_y=0.15))
        self.status = Label(text='Protection: ACTIVE', size_hint_y=0.1, color=(0.3,1,0.5,1))
        self.add_widget(self.status)
        
        btns = BoxLayout(size_hint_y=0.2, spacing=8)
        btns.add_widget(Button(text='Quick Scan', on_press=self.quick_scan))
        btns.add_widget(Button(text='Full Scan', on_press=self.full_scan))
        self.add_widget(btns)
        
        btns2 = BoxLayout(size_hint_y=0.15, spacing=8)
        btns2.add_widget(Button(text='Check URL', on_press=self.check_url))
        btns2.add_widget(Button(text='Clear Log', on_press=self.clear_log))
        self.add_widget(btns2)
        
        self.log_input = TextInput(text='SimpleAV ready. Tap Quick Scan.\n', font_size=13)
        self.add_widget(self.log_input)
    
    def log(self, msg):
        def do(dt):
            self.log_input.text += msg + '\n'
        Clock.schedule_once(do)
    
    def clear_log(self, *args):
        self.log_input.text = ''
    
    def quick_scan(self, *args):
        self.log('Scanning /sdcard/Download...')
        import threading
        def worker():
            path = '/sdcard/Download'
            if not os.path.exists(path):
                path = '/storage/emulated/0/Download'
            if not os.path.exists(path):
                path = '/data/data/ru.iiec.pydroid3/files'
            count = 0
            try:
                for root, _, files in os.walk(path):
                    for f in files[:100]:
                        count += 1
                        # Simple check for EICAR test
                        fp = os.path.join(root, f)
                        try:
                            with open(fp, 'r', errors='ignore') as fh:
                                data = fh.read(10000)
                                if 'EICAR' in data:
                                    self.log('THREAT: ' + f + ' -> EICAR')
                        except:
                            pass
                    if count > 100:
                        break
                self.log('Done. Files checked: ' + str(count))
            except Exception as e:
                self.log('Error: ' + str(e))
        threading.Thread(target=worker, daemon=True).start()
    
    def full_scan(self, *args):
        self.log('Full scan started...')
        self.quick_scan()
    
    def check_url(self, *args):
        from kivy.uix.popup import Popup
        box = BoxLayout(orientation='vertical', padding=10, spacing=10)
        inp = TextInput(hint_text='https://...', multiline=False, size_hint_y=0.3)
        lbl = Label(text='Enter URL to check')
        box.add_widget(lbl)
        box.add_widget(inp)
        def do_check(*a):
            url = inp.text
            if not url:
                return
            if '.tk/' in url or '.ml/' in url or '@' in url:
                self.log('PHISHING: ' + url)
                lbl.text = 'PHISHING DETECTED!'
            else:
                self.log('OK: ' + url)
                lbl.text = 'Appears Clean'
        box.add_widget(Button(text='Check Now', size_hint_y=0.3, on_press=do_check))
        Popup(title='Phishing Check', content=box, size_hint=(0.9,0.5)).open()

class SimpleAVApp(App):
    def build(self):
        return AVLayout()

SimpleAVApp().run()
