import tkinter as tk
from tkinter import ttk

def modificar_configuracion():
    archivo = 'wowchat.conf'

    token_nuevo = token.get()
    account_nuevo = account.get()
    password_nuevo = password.get()
    character_nuevo = character.get()
    channel_nuevo = channel.get()

    with open(archivo, 'r') as file:
        lineas = file.readlines()

    for i, linea in enumerate(lineas):
        if 'token=' in linea:
            lineas[i] = f"  token={token_nuevo}\n"
        elif 'account=' in linea:
            lineas[i] = f"  account={account_nuevo}\n"
        elif 'password=' in linea:
            lineas[i] = f"  password={password_nuevo}\n"
        elif 'character=' in linea:
            lineas[i] = f"  character={character_nuevo}\n"
        elif 'channel=' in linea and i == 148:  
            lineas[i] = f"      channel={channel_nuevo}\n"

    with open(archivo, 'w') as file:
        file.writelines(lineas)

    print("Configuracion actualizada")

ventana = tk.Tk()
ventana.title("MateWowChat")

marco = ttk.Frame(ventana, padding="20", relief="ridge", borderwidth=2)
marco.grid(row=0, column=0)

ttk.Label(marco, text="Token DC:").grid(row=0, column=0, sticky="w", pady=(5, 2))
token = ttk.Entry(marco)
token.grid(row=0, column=1, pady=(5, 2))

ttk.Label(marco, text="Cuenta:").grid(row=1, column=0, sticky="w", pady=2)
account = ttk.Entry(marco)
account.grid(row=1, column=1, pady=2)

ttk.Label(marco, text="Contraseña:").grid(row=2, column=0, sticky="w", pady=2)
password = ttk.Entry(marco)
password.grid(row=2, column=1, pady=2)

ttk.Label(marco, text="Personaje:").grid(row=3, column=0, sticky="w", pady=2)
character = ttk.Entry(marco)
character.grid(row=3, column=1, pady=2)

ttk.Label(marco, text="ID del Canal:").grid(row=4, column=0, sticky="w", pady=2)
channel = ttk.Entry(marco)
channel.grid(row=4, column=1, pady=2)

boton = ttk.Button(marco, text="Guardar cambios", command=modificar_configuracion)
boton.grid(row=5, column=0, columnspan=2, pady=(10, 5))

ventana.mainloop()
